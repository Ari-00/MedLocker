import { passwordVisibility } from './password.js';
import { setupPasswordReset, setupSocial } from './auth-options.js';

const byId = id => document.getElementById(id);
const form = byId('access');
const canvas = byId('pdf');
const pdfBuild = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/build/';
let session;
let pdfLibrary;
let doctorToken;

function status(message, error = false) {
  byId('status').textContent = message;
  byId('status').classList.toggle('error', error);
}

function clearReport() {
  const closing = session;
  session = undefined;
  if (closing) {
    clearInterval(closing.timer);
    closing.request.abort();
    closing.render?.cancel();
    closing.loading?.destroy().catch(() => {});
    if (closing.bytes?.byteLength) closing.bytes.fill(0);
    if (closing.downloadUrl) URL.revokeObjectURL(closing.downloadUrl);
    closing.bytes = undefined;
    closing.pdf = undefined;
  }
  canvas.width = canvas.height = 0;
  byId('countdown').textContent = '';
  byId('pages').textContent = '';
  byId('page-text').textContent = '';
  byId('page-text').parentElement.open = false;
  byId('report').hidden = true;
  byId('entry').hidden = !doctorToken;
  form.reset();
  form.querySelector('button').disabled = false;
}

function signOutDoctor() {
  doctorToken = undefined;
  clearReport();
  byId('doctor-login').hidden = false;
  doctorAuth.reset();
  setDoctorRegistration(false);
}

const doctorAuth = byId('doctor-auth');
const hidePassword = passwordVisibility(doctorAuth);
const doctorSubmit = byId('doctor-submit');
let createDoctor = false;

function setDoctorRegistration(enabled) {
  createDoctor = enabled;
  byId('doctor-full-name').hidden = byId('doctor-invite').hidden = !enabled;
  doctorAuth.elements.fullName.required = doctorAuth.elements.inviteCode.required = enabled;
  doctorAuth.elements.inviteCode.value = '';
  doctorAuth.elements.password.minLength = enabled ? 8 : 0;
  doctorAuth.elements.password.autocomplete = enabled ? 'new-password' : 'current-password';
  byId('doctor-title').textContent = enabled ? 'Create doctor account' : 'Doctor sign-in';
  doctorSubmit.textContent = enabled ? 'Create account' : 'Sign in as doctor';
  byId('doctor-toggle').textContent = enabled ? 'Back to doctor sign-in' : 'Create doctor account';
  hidePassword();
}

byId('doctor-toggle').addEventListener('click', () => {
  if (doctorSubmit.disabled) return;
  setDoctorRegistration(!createDoctor);
  status('');
  (createDoctor ? doctorAuth.elements.fullName : doctorAuth.elements.email).focus();
});

doctorAuth.addEventListener('submit', async event => {
  event.preventDefault();
  const { fullName, email, password, inviteCode } = Object.fromEntries(new FormData(doctorAuth));
  doctorSubmit.disabled = true;
  status(createDoctor ? 'Creating account…' : 'Signing in…');
  try {
    const response = await fetch('/api/v1/auth/doctor/' + (createDoctor ? 'register' : 'login'), {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(createDoctor ? { fullName, email, password, inviteCode } : { email, password }), cache: 'no-store'
    });
    const result = await response.json();
    if (!response.ok) throw new Error(result.message || 'Could not complete doctor authentication.');
    enterDoctor(result);
  } catch (error) {
    status(error.message, true);
  } finally {
    doctorSubmit.disabled = false;
  }
});

function enterDoctor(result) {
  doctorToken = result.token;
  doctorAuth.reset();
  byId('doctor-login').hidden = true;
  byId('entry').hidden = false;
  status('');
  form.elements.pin.focus();
}
byId('doctor-logout').addEventListener('click', () => {
  signOutDoctor();
  status('Signed out.');
  doctorAuth.elements.email.focus();
});

function tick(active) {
  if (active !== session) return;
  const seconds = Math.max(0, Math.ceil((active.expires - Date.now()) / 1000));
  if (!seconds) {
    clearReport();
    status('This PIN has expired. Ask the patient for a new PIN.', true);
    form.elements.pin.focus();
    return;
  }
  byId('countdown').textContent = Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
}

async function renderPage(active, number) {
  if (active !== session || active.rendering || number < 1 || number > active.pdf.numPages) return;
  active.rendering = true;
  byId('previous').disabled = byId('next').disabled = true;
  const page = await active.pdf.getPage(number);
  if (active !== session) return;
  const original = page.getViewport({ scale: 1 });
  const viewport = page.getViewport({ scale: 1400 / Math.max(original.width, original.height) });
  canvas.width = Math.ceil(viewport.width);
  canvas.height = Math.ceil(viewport.height);
  active.render = page.render({ canvasContext: canvas.getContext('2d'), viewport });
  await active.render.promise;
  if (active !== session) return;
  const text = await page.getTextContent();
  if (active !== session) return;
  byId('page-text').textContent = text.items.map(item => (item.str || '') + (item.hasEOL ? '\n' : ' ')).join('').trim() || 'This page has no embedded text.';
  canvas.setAttribute('aria-label', 'PDF page ' + number);
  byId('pages').textContent = number + ' / ' + active.pdf.numPages;
  active.page = number;
  active.render = undefined;
  active.rendering = false;
  byId('previous').disabled = number === 1;
  byId('next').disabled = number === active.pdf.numPages;
}

function failed(active, error) {
  if (session !== active) return;
  clearReport();
  status(error.message, true);
  form.elements.pin.focus();
}

form.addEventListener('submit', async event => {
  event.preventDefault();
  if (session || !doctorToken) return;
  const active = { request: new AbortController() };
  session = active;
  form.querySelector('button').disabled = true;
  status('Opening report…');
  try {
    pdfLibrary ??= await import(pdfBuild + 'pdf.mjs');
    if (session !== active) return;
    pdfLibrary.GlobalWorkerOptions.workerSrc = pdfBuild + 'pdf.worker.mjs';
    const response = await fetch('/api/v1/share/access', {
      method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + doctorToken },
      body: JSON.stringify({ pin: form.elements.pin.value }), cache: 'no-store', signal: active.request.signal
    });
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}));
      if (response.status === 401 && session === active) {
        signOutDoctor();
        status('Your session expired. Sign in as a doctor again.', true);
        doctorAuth.elements.email.focus();
        return;
      }
      throw new Error(problem.message || 'Could not open the report.');
    }
    active.expires = Date.parse(response.headers.get('X-Expires-At'));
    if (!Number.isFinite(active.expires) || active.expires <= Date.now()) {
      throw new Error('This PIN has expired. Ask the patient for a new PIN.');
    }
    active.timer = setInterval(() => tick(active), 1000);
    tick(active);
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (session !== active) { bytes.fill(0); return; }
    active.bytes = bytes;
    // PDF.js may transfer its copy to a worker, which is destroyed when the report closes.
    active.loading = pdfLibrary.getDocument({
      data: bytes.slice(),
      isEvalSupported: false,
      cMapUrl: pdfBuild + '../cmaps/',
      cMapPacked: true,
      standardFontDataUrl: pdfBuild + '../standard_fonts/'
    });
    active.pdf = await active.loading.promise;
    if (session !== active) return;
    await renderPage(active, 1);
    if (session !== active) return;
    form.reset();
    byId('entry').hidden = true;
    byId('report').hidden = false;
    status('');
    byId('close').focus();
  } catch (error) { failed(active, error); }
});

for (const [id, step] of [['previous', -1], ['next', 1]]) {
  byId(id).addEventListener('click', () => {
    const active = session;
    if (active) renderPage(active, active.page + step).catch(error => failed(active, error));
  });
}
canvas.addEventListener('contextmenu', event => event.preventDefault());
byId('download').addEventListener('click', () => {
  if (!session?.bytes || Date.now() >= session.expires) {
    if (session) tick(session);
    return;
  }
  session.downloadUrl ??= URL.createObjectURL(new Blob([session.bytes], { type: 'application/pdf' }));
  const link = document.createElement('a');
  link.href = session.downloadUrl;
  link.download = 'MedLocker-report.pdf';
  link.click();
});
byId('close').addEventListener('click', () => {
  clearReport();
  status('Report closed.');
  form.elements.pin.focus();
});
window.addEventListener('pagehide', signOutDoctor);
document.addEventListener('visibilitychange', () => {
  if (session?.expires) tick(session);
});
setupPasswordReset(byId('forgot'), doctorAuth.elements.email, status);
setupSocial({ doctor: true, getToken: () => doctorToken, enter: enterDoctor, notify: status });
