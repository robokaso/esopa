// Toggles the password fields of a form between hidden and visible text. It listens
// on the document, so it also works for the forms htmx swaps in.
document.addEventListener('change', (event) => {
  const toggle = event.target;
  if (toggle.matches('input[data-show-password]')) {
    for (const field of toggle.form.querySelectorAll('input.password')) {
      field.type = toggle.checked ? 'text' : 'password';
    }
  }
});
