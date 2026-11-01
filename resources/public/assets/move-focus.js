// After htmx swaps in a new page, moves focus to its heading, as a full page load
// moves focus to the start of the page, so screen readers announce the new page.
// When the page sets autofocus, like a form showing a problem, htmx has already
// focused that element, so focus stays there.
document.addEventListener('htmx:after:swap', () => {
  if (!document.querySelector('[autofocus]')) {
    document.querySelector('h1')?.focus();
  }
});
