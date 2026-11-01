// When htmx can't reach esopa, says so below the page's heading. Without htmx the
// browser would show its own error page; with it, nothing would happen. The message
// is announced to screen readers and takes focus.
document.addEventListener('htmx:error', () => {
  let message = document.getElementById('unreachable');
  if (!message) {
    message = document.createElement('p');
    message.id = 'unreachable';
    message.setAttribute('role', 'alert');
    message.tabIndex = -1;
    document.querySelector('main h1').after(message);
  }
  message.textContent = "esopa couldn't be reached. Check your connection and try again.";
  message.focus();
});
