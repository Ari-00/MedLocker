const providers = { google: 'Google', apple: 'Apple', microsoft: 'Microsoft' };
const socialHeaders = { 'X-MedLocker-Auth': '1' };
const jsonHeaders = { 'Content-Type': 'application/json' };

async function post(path, data, headers = {}) {
  const response = await fetch('/api/v1/auth/' + path, {
    method: 'POST', headers: { ...jsonHeaders, ...headers },
    body: JSON.stringify(data), cache: 'no-store'
  });
  if (response.status === 204 || response.status === 202) return;
  const result = await response.json();
  if (!response.ok) throw new Error(result.message || 'Try again.');
  return result;
}

export function setupPasswordReset(trigger, emailInput, notify) {
  const dialog = document.createElement('dialog');
  dialog.setAttribute('aria-labelledby', 'reset-title');
  dialog.innerHTML = `
    <div class="row">
      <h2 id="reset-title">Reset your password</h2>
      <button type="button" class="quiet" id="reset-back">Back to sign-in</button>
    </div>
    <p>Use the email registered to your MedLocker account.</p>
    <form id="reset-request" method="post" aria-describedby="reset-status">
      <label>Email <input name="email" type="email" autocomplete="email" maxlength="254" required></label>
      <button type="submit">Email me a code</button>
    </form>
    <form id="reset-confirm" method="post" aria-describedby="reset-status" hidden>
      <label>Email code <input name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required></label>
      <label>New password <input name="password" type="password" autocomplete="new-password" minlength="8" maxlength="72" required></label>
      <label>Confirm new password <input name="confirmPassword" type="password" autocomplete="new-password" minlength="8" maxlength="72" required></label>
      <button type="submit">Change password</button>
      <button type="button" class="quiet" id="reset-resend">Request another code</button>
    </form>
    <p id="reset-status" role="status" aria-live="polite"></p>`;
  dialog.className = 'reset-dialog';
  document.body.append(dialog);
  const requestForm = dialog.querySelector('#reset-request');
  const confirmForm = dialog.querySelector('#reset-confirm');
  const status = dialog.querySelector('#reset-status');
  let resetAttempt = 0;
  const tell = (message, error = false) => {
    status.textContent = message;
    status.classList.toggle('error', error);
  };

  trigger.addEventListener('click', () => {
    resetAttempt++;
    requestForm.elements.email.value = emailInput.value;
    requestForm.hidden = false;
    confirmForm.hidden = true;
    tell('');
    dialog.showModal();
  });
  dialog.querySelector('#reset-back').addEventListener('click', () => dialog.close());
  dialog.addEventListener('close', () => {
    requestForm.reset();
    confirmForm.reset();
    tell('');
  });
  dialog.querySelector('#reset-resend').addEventListener('click', () => {
    requestForm.hidden = false;
    confirmForm.hidden = true;
    confirmForm.reset();
    requestForm.elements.email.focus();
  });

  async function submit(form, path, values, onSuccess) {
    const attempt = resetAttempt;
    const button = form.querySelector('[type=submit]');
    button.disabled = true;
    tell(path === 'request' ? 'Requesting a code…' : 'Changing password…');
    try {
      await post('password/' + path, values);
      if (dialog.open && attempt === resetAttempt) onSuccess();
    } catch (error) {
      if (dialog.open && attempt === resetAttempt) tell(error.message, true);
    } finally {
      button.disabled = false;
    }
  }

  requestForm.addEventListener('submit', event => {
    event.preventDefault();
    submit(requestForm, 'request', { email: requestForm.elements.email.value }, () => {
      requestForm.hidden = true;
      confirmForm.hidden = false;
      tell('If an account uses that email, a code will arrive shortly. It expires in 10 minutes.');
      confirmForm.elements.code.focus();
    });
  });
  confirmForm.addEventListener('submit', event => {
    event.preventDefault();
    const values = Object.fromEntries(new FormData(confirmForm));
    if (values.password !== values.confirmPassword) {
      tell('The new passwords do not match.', true);
      confirmForm.elements.confirmPassword.focus();
      return;
    }
    submit(confirmForm, 'reset', { email: requestForm.elements.email.value, ...values }, () => {
      emailInput.value = requestForm.elements.email.value;
      emailInput.form.elements.password.value = '';
      dialog.close();
      notify('Password changed. Sign in with your new password.');
    });
  });
}

export async function setupSocial({ doctor, getToken, enter, notify }) {
  const returning = new URLSearchParams(location.search).get('social') === 'return';
  if (returning) {
    history.replaceState(null, '', location.pathname + location.hash);
    try {
      const result = await post('social/result', {}, socialHeaders);
      if (result?.account) await enter(result.account);
      if (result?.message) notify(result.message, !result.account);
      if (!result) notify('Provider sign-in expired. Try again.', true);
    } catch (error) {
      notify(error.message, true);
    }
  }
  try {
    const response = await fetch('/api/v1/auth/social/providers', { cache: 'no-store' });
    if (!response.ok) return;
    const enabled = await response.json();
    for (const link of [false, true]) {
      const group = document.getElementById(link ? 'social-connect' : 'social-signin');
      for (const provider of enabled) {
        if (!Object.hasOwn(providers, provider)) continue;
        const button = document.createElement('button');
        button.className = 'quiet';
        button.type = 'button';
        button.textContent = (link ? 'Connect ' : 'Continue with ') + providers[provider];
        button.addEventListener('click', async () => {
          button.disabled = true;
          try {
            const headers = { ...socialHeaders };
            if (link) headers.Authorization = 'Bearer ' + getToken();
            const result = await post('social/start', { provider, doctor, link }, headers);
            location.assign(result.url);
          } catch (error) {
            notify(error.message, true);
            button.disabled = false;
          }
        });
        group.querySelector('.provider-buttons').append(button);
      }
      group.hidden = group.querySelector('.provider-buttons').childElementCount === 0;
    }
  } catch {
    // Password sign-in remains available if the provider list cannot load.
  }
}
