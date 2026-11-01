// While htmx submits a form, marks its button busy: Pico shows a spinner on it and
// screen readers announce it. The page htmx swaps in replaces the button; if the
// request fails instead, the mark is removed.
document.addEventListener('htmx:before:request', (event) => {
  if (event.target instanceof HTMLFormElement) {
    event.target.querySelector('button')?.setAttribute('aria-busy', 'true');
  }
});
document.addEventListener('htmx:finally:request', (event) => {
  if (event.target instanceof HTMLFormElement) {
    event.target.querySelector('button')?.removeAttribute('aria-busy');
  }
});
