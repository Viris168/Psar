/** Money comes from the API as a string/number with 2 decimals. Display only; never calculate totals here. */
export function formatUsd(amount: number | string): string {
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(Number(amount))
}

export function formatKhr(amount: number | string): string {
  return `${new Intl.NumberFormat('en-US').format(Math.round(Number(amount)))} ៛`
}
