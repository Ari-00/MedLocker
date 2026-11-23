export function passwordVisibility(form) {
  const input = form.elements.password;
  const button = form.querySelector('[data-password-toggle]');
  function show(visible) {
    input.type = visible ? 'text' : 'password';
    button.setAttribute('aria-pressed', String(visible));
  }

  button.addEventListener('click', () => show(input.type === 'password'));
  form.addEventListener('reset', () => show(false));
  return () => show(false);
}
