// Toggles the password fields of a form between hidden and visible text.
for (const toggle of document.querySelectorAll('input[data-show-password]')) {
  toggle.addEventListener('change', () => {
    for (const field of toggle.form.querySelectorAll('input.password')) {
      field.type = toggle.checked ? 'text' : 'password';
    }
  });
}
