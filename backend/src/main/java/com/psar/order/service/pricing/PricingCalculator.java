package com.psar.order.service.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Pure pricing logic: no Spring, no repositories, no I/O.
 * CheckoutService (and the checkout quote) load the data, pass it in, and use the result.
 * <p>
 * Order of calculation (architecture.md §5.5, CLAUDE.md "Pricing order"):
 * <ol>
 *   <li>line total = unit price × quantity, per line; subtotal = sum of the line totals</li>
 *   <li>{@code productDiscount}: the best item promotion per line (buy X get Y fills {@code freeQuantity}
 *       and its value goes in the line's {@code discountAmount}, as does a percentage / fixed promotion)</li>
 *   <li>{@code orderDiscount}: one order-level promotion on what is left: the coupon if there is one, otherwise
 *       the best automatic one; FREE_DELIVERY takes the slot by making the delivery fee 0</li>
 *   <li>{@code rewardDiscount}: one reward voucher from points (Phase 7)</li>
 *   <li>+ delivery fee (from DeliveryQuoteService; Phase 8 makes it 0 for members)</li>
 *   <li>total = subtotal − discounts + delivery fee, never below 0</li>
 * </ol>
 * The exact rules are in task/task6.md "Pricing rules". Details decided here:
 * <ul>
 *   <li><b>Item offers.</b> Per line, every automatic ITEM offer listing the line's product whose minimum (checked
 *       against the subtotal before discounts) is met is a candidate; the largest discount wins, ties → the one
 *       that ends first, then the lowest id. An offer that would take nothing off a line is not applied there.
 *       Item discounts never exceed the line total.</li>
 *   <li><b>Item coupon.</b> Replaces the line's automatic offer only where it takes strictly more off (a tie keeps
 *       the automatic one). If it wins on at least one line it is {@code APPLIED} and holds the order slot, so no
 *       automatic ORDER offer applies; if it wins on none it is {@code NOT_BETTER}.</li>
 *   <li><b>Order coupon.</b> Takes the slot whenever its minimum is met (checked against the subtotal after item
 *       discounts), even when an automatic ORDER offer would give more (customer's choice).</li>
 *   <li>A coupon that does not apply ({@code BELOW_MINIMUM} / {@code NOT_BETTER}) is priced as if it had not been
 *       entered: the best automatic ORDER offer takes the slot. The quote shows that price; checkout rejects it.</li>
 *   <li><b>Order discount</b> is taken from the subtotal after item discounts and never exceeds it (a fixed amount
 *       larger than what is left takes it to 0). PERCENTAGE is capped by {@code maxDiscount}. FREE_DELIVERY's
 *       discount is the delivery fee it waives. Automatic ORDER offers are compared by that amount (ties as for
 *       items); one that would take nothing off is not applied.</li>
 *   <li>{@code appliedOffers}: item offers in the order of the first line they apply to (lines summed), then the
 *       order offer. An applied coupon is always listed, even when it took 0 off (e.g. free delivery on a free
 *       delivery), so its use is counted.</li>
 * </ul>
 * Every amount is scale 2, {@link RoundingMode#HALF_UP}; a line total is rounded once, after unit price × quantity
 * (prices from the database are already scale 2, so this only matters for odd input), and each line's discount is
 * rounded on its own.
 */
public final class PricingCalculator {

    public static final int MONEY_SCALE = 2;

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** Larger discount first; ties → ends first, then lowest id. */
    private static final Comparator<Candidate> BEST = Comparator
            .comparing(Candidate::discount, Comparator.reverseOrder())
            .thenComparing(c -> c.offer().endsAt())
            .thenComparing(c -> c.offer().id());

    /** Rounds a money amount to 2 decimals, HALF_UP. */
    public static BigDecimal money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Prices an order: line totals, subtotal, promotions, delivery fee and total. */
    public PricingResult price(PricingInput input) {
        Objects.requireNonNull(input, "input");
        input.offers().forEach(PricingCalculator::validate);
        BigDecimal fee = money(nonNegative(input.deliveryFee(), "deliveryFee"));
        List<BaseLine> baseLines = input.lines().stream().map(PricingCalculator::baseLine).toList();
        BigDecimal subtotal = money(baseLines.stream().map(BaseLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        Optional<PricingInput.Offer> coupon = input.offers().stream().filter(PricingInput.Offer::coupon).findFirst();
        List<PricingInput.Offer> automaticItem = input.offers().stream()
                .filter(o -> !o.coupon() && o.level() == PricingInput.OfferLevel.ITEM && meetsMinimum(o, subtotal))
                .toList();
        List<PricingInput.Offer> automaticOrder = input.offers().stream()
                .filter(o -> !o.coupon() && o.level() == PricingInput.OfferLevel.ORDER)
                .toList();

        // 1. Item offers: the best automatic one per line, then an item coupon where it gives more.
        PricingResult.CouponStatus couponStatus = PricingResult.CouponStatus.NONE;
        List<Candidate> chosen = new ArrayList<>(baseLines.size());
        for (BaseLine line : baseLines) {
            chosen.add(best(automaticItem.stream().map(o -> candidate(o, line)).toList()));
        }
        if (coupon.isPresent() && coupon.get().level() == PricingInput.OfferLevel.ITEM) {
            couponStatus = applyItemCoupon(coupon.get(), baseLines, subtotal, chosen);
        }

        List<PricingResult.Line> lines = new ArrayList<>(baseLines.size());
        Map<Long, AppliedTotal> applied = new LinkedHashMap<>();
        for (int i = 0; i < baseLines.size(); i++) {
            BaseLine base = baseLines.get(i);
            Candidate pick = chosen.get(i);
            if (pick == null) {
                lines.add(base.priced(0, ZERO, null));
            } else {
                lines.add(base.priced(pick.freeQuantity(), pick.discount(), pick.offer().id()));
                applied.computeIfAbsent(pick.offer().id(), id -> new AppliedTotal(PricingInput.OfferLevel.ITEM))
                        .add(pick.discount());
            }
        }
        BigDecimal productDiscount = money(lines.stream().map(PricingResult.Line::discountAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        BigDecimal afterItems = subtotal.subtract(productDiscount);

        // 2. The order slot: an applied item coupon holds it; else an order coupon whose minimum is met; else the
        //    best automatic ORDER offer.
        Candidate orderPick = null;
        boolean couponHoldsSlot = couponStatus == PricingResult.CouponStatus.APPLIED;
        if (coupon.isPresent() && coupon.get().level() == PricingInput.OfferLevel.ORDER) {
            if (meetsMinimum(coupon.get(), afterItems)) {
                orderPick = orderCandidate(coupon.get(), afterItems, fee);
                couponStatus = PricingResult.CouponStatus.APPLIED;
                couponHoldsSlot = true;
            } else {
                couponStatus = PricingResult.CouponStatus.BELOW_MINIMUM;
            }
        }
        if (!couponHoldsSlot) {
            orderPick = best(automaticOrder.stream()
                    .filter(o -> meetsMinimum(o, afterItems))
                    .map(o -> orderCandidate(o, afterItems, fee))
                    .toList());
        }

        BigDecimal orderDiscount = ZERO;
        BigDecimal deliveryFee = fee;
        if (orderPick != null) {
            if (orderPick.offer().type() == PricingInput.OfferType.FREE_DELIVERY) {
                deliveryFee = ZERO;
            } else {
                orderDiscount = orderPick.discount();
            }
            applied.computeIfAbsent(orderPick.offer().id(), id -> new AppliedTotal(PricingInput.OfferLevel.ORDER))
                    .add(orderPick.discount());
        }
        BigDecimal rewardDiscount = ZERO;

        List<PricingResult.AppliedOffer> appliedOffers = applied.entrySet().stream()
                .map(e -> new PricingResult.AppliedOffer(e.getKey(), e.getValue().level, money(e.getValue().sum)))
                .toList();
        BigDecimal discounts = productDiscount.add(orderDiscount).add(rewardDiscount);
        return new PricingResult(lines, subtotal, productDiscount, orderDiscount, rewardDiscount, deliveryFee,
                total(subtotal, discounts, deliveryFee), appliedOffers, couponStatus);
    }

    /** total = subtotal - discounts + deliveryFee, never below zero. */
    public BigDecimal total(BigDecimal subtotal, BigDecimal discounts, BigDecimal deliveryFee) {
        BigDecimal total = subtotal.subtract(discounts).add(deliveryFee);
        return money(total.max(BigDecimal.ZERO));
    }

    /** Puts the item coupon on the lines where it beats the automatic offer; returns the coupon status. */
    private static PricingResult.CouponStatus applyItemCoupon(PricingInput.Offer coupon, List<BaseLine> lines,
                                                              BigDecimal subtotal, List<Candidate> chosen) {
        if (!meetsMinimum(coupon, subtotal)) {
            return PricingResult.CouponStatus.BELOW_MINIMUM;
        }
        boolean won = false;
        for (int i = 0; i < lines.size(); i++) {
            Candidate couponOnLine = candidate(coupon, lines.get(i));
            if (couponOnLine == null) {
                continue;
            }
            Candidate current = chosen.get(i);
            BigDecimal currentDiscount = current == null ? ZERO : current.discount();
            if (couponOnLine.discount().compareTo(currentDiscount) > 0) {
                chosen.set(i, couponOnLine);
                won = true;
            }
        }
        return won ? PricingResult.CouponStatus.APPLIED : PricingResult.CouponStatus.NOT_BETTER;
    }

    /** What an ITEM offer takes off one line; null if it does not cover the line or takes nothing off. */
    private static Candidate candidate(PricingInput.Offer offer, BaseLine line) {
        if (!offer.productIds().contains(line.productId())) {
            return null;
        }
        int free = 0;
        BigDecimal discount = switch (offer.type()) {
            case PERCENTAGE -> capped(percentOf(line.lineTotal(), offer.value()), offer.maxDiscount());
            case FIXED_AMOUNT -> money(offer.value().multiply(BigDecimal.valueOf(line.quantity())));
            case BUY_X_GET_Y -> {
                int group = offer.buyQuantity() + offer.getQuantity();
                free = (line.quantity() / group) * offer.getQuantity();
                yield money(line.unitPrice().multiply(BigDecimal.valueOf(free)));
            }
            case FREE_DELIVERY -> throw new IllegalStateException("validated: FREE_DELIVERY is ORDER only");
        };
        discount = discount.min(line.lineTotal());
        return discount.signum() > 0 ? new Candidate(offer, discount, free) : null;
    }

    /**
     * What an ORDER offer takes off: never more than what is left after item discounts (FREE_DELIVERY: the fee).
     * A coupon is returned even at 0 (it was chosen); for automatic offers, 0 means it does not apply.
     */
    private static Candidate orderCandidate(PricingInput.Offer offer, BigDecimal afterItems, BigDecimal fee) {
        BigDecimal discount = switch (offer.type()) {
            case PERCENTAGE -> capped(percentOf(afterItems, offer.value()), offer.maxDiscount());
            case FIXED_AMOUNT -> money(offer.value());
            case FREE_DELIVERY -> fee;
            case BUY_X_GET_Y -> throw new IllegalStateException("validated: BUY_X_GET_Y is ITEM only");
        };
        if (offer.type() != PricingInput.OfferType.FREE_DELIVERY) {
            discount = discount.min(afterItems);
        }
        return discount.signum() > 0 || offer.coupon() ? new Candidate(offer, discount, 0) : null;
    }

    private static Candidate best(List<Candidate> candidates) {
        return candidates.stream().filter(Objects::nonNull).min(BEST).orElse(null);
    }

    private static boolean meetsMinimum(PricingInput.Offer offer, BigDecimal amount) {
        return offer.minOrderAmount() == null || amount.compareTo(offer.minOrderAmount()) >= 0;
    }

    private static BigDecimal percentOf(BigDecimal amount, BigDecimal percent) {
        return amount.multiply(percent).divide(HUNDRED, MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal capped(BigDecimal discount, BigDecimal maxDiscount) {
        return maxDiscount == null ? discount : discount.min(money(maxDiscount));
    }

    /** The database enforces the same rules (V6 CHECKs); anything else is a programming error. */
    private static void validate(PricingInput.Offer offer) {
        String id = "offer " + offer.id() + ": ";
        switch (offer.type()) {
            case PERCENTAGE -> {
                requireValue(offer, id);
                if (offer.value().compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException(id + "percentage must be 0-100");
                }
            }
            case FIXED_AMOUNT -> requireValue(offer, id);
            case BUY_X_GET_Y -> {
                if (offer.level() != PricingInput.OfferLevel.ITEM) {
                    throw new IllegalArgumentException(id + "BUY_X_GET_Y must be ITEM level");
                }
                if (offer.buyQuantity() == null || offer.getQuantity() == null
                        || offer.buyQuantity() < 1 || offer.getQuantity() < 1) {
                    throw new IllegalArgumentException(id + "BUY_X_GET_Y needs buy and get quantities >= 1");
                }
            }
            case FREE_DELIVERY -> {
                if (offer.level() != PricingInput.OfferLevel.ORDER) {
                    throw new IllegalArgumentException(id + "FREE_DELIVERY must be ORDER level");
                }
            }
        }
        if (offer.maxDiscount() != null && offer.maxDiscount().signum() < 0) {
            throw new IllegalArgumentException(id + "maxDiscount must not be negative");
        }
    }

    private static void requireValue(PricingInput.Offer offer, String id) {
        if (offer.value() == null || offer.value().signum() < 0) {
            throw new IllegalArgumentException(id + offer.type() + " needs a non-negative value");
        }
    }

    private static BaseLine baseLine(PricingInput.Line line) {
        if (line.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive for product " + line.productId());
        }
        BigDecimal unitPrice = nonNegative(line.unitPrice(), "unitPrice");
        BigDecimal lineTotal = money(unitPrice.multiply(BigDecimal.valueOf(line.quantity())));
        return new BaseLine(line.productId(), line.quantity(), money(unitPrice), lineTotal);
    }

    private static BigDecimal nonNegative(BigDecimal amount, String name) {
        Objects.requireNonNull(amount, name);
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return amount;
    }

    private record BaseLine(Long productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal) {
        PricingResult.Line priced(int freeQuantity, BigDecimal discount, Long offerId) {
            return new PricingResult.Line(productId, quantity, freeQuantity, unitPrice, discount, lineTotal, offerId);
        }
    }

    private record Candidate(PricingInput.Offer offer, BigDecimal discount, int freeQuantity) {
    }

    private static final class AppliedTotal {
        private final PricingInput.OfferLevel level;
        private BigDecimal sum = BigDecimal.ZERO;

        AppliedTotal(PricingInput.OfferLevel level) {
            this.level = level;
        }

        void add(BigDecimal amount) {
            sum = sum.add(amount);
        }
    }
}
