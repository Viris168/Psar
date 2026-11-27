package com.psar.payment.service.khqr;

/** The KHQR string the customer scans, and its md5 (how Bakong finds the payment). */
public record KhqrCode(String qr, String md5) {
}
