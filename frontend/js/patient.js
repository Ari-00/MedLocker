import { passwordVisibility } from './password.js';
import { setupPasswordReset, setupSocial } from './auth-options.js';

const byId = id => document.getElementById(id);
const auth = byId('auth');
const hidePassword = passwordVisibility(auth);
const upload = byId('upload');
let token;
let register = false;
let shareTimer;
let shareExpires;
let latestShare;

function status(message, error = false) {
  byId('status').textContent = message;
  byId('status').classList.toggle('error', error);
}

async function request(path, options = {}, raw = false) {
  const session = token;
  const headers = new Headers(options.headers);
  if (session) headers.set('Authorization', 'Bearer ' + session);
  const response = await fetch('/api/v1' + path, { ...options, headers, cache: 'no-store' });
  if (response.status === 401 && session && session === token) signOut();
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.message || 'Request failed. Try again.');
  }
  if (raw) return response;
  if (response.status !== 204) return response.json();
}

async function run(button, label, action) {
  const session = token;
  const original = button.textContent;
  button.disabled = true;
  button.textContent = label;
  status('');
  try { await action(); }
  catch (error) {
    if (!session || session === token || !token) status(error.message, true);
  }
  finally { button.disabled = false; button.textContent = original; }
}

function signOut() {
  token = undefined;
  closePreview();
  stopCamera();
  clearGallery();
  byId('profile-form').reset();
  byId('gallery').hidden = true;
  byId('profile').hidden = true;
  byId('portal-back').hidden = true;
  latestShare = undefined;
  byId('vault').hidden = true;
  byId('account').hidden = false;
  byId('documents').replaceChildren();
  clearShare();
  auth.reset();
  setRegister(false);
  upload.reset();
  byId('empty').hidden = true;
  auth.elements.email.focus();
  history.replaceState(null, '', location.pathname);
}

byId('logout').addEventListener('click', () => { signOut(); status('Signed out.'); });
function setRegister(enabled) {
  register = enabled;
  hidePassword();
  byId('name-label').hidden = !register;
  auth.elements.fullName.required = register;
  auth.elements.password.autocomplete = register ? 'new-password' : 'current-password';
  auth.elements.password.minLength = register ? 8 : 0;
  const action = register ? 'Create account' : 'Sign in';
  byId('account-title').textContent = action;
  byId('auth-submit').textContent = action;
  byId('toggle').textContent = register ? 'Back to sign in' : 'Create an account';
}

byId('toggle').addEventListener('click', () => {
  if (byId('auth-submit').disabled) return;
  setRegister(!register);
  status('');
});
auth.addEventListener('submit', event => {
  event.preventDefault();
  const { email, password, fullName } = Object.fromEntries(new FormData(auth));
  run(byId('auth-submit'), 'Please wait…', async () => {
    const account = await request('/auth/' + (register ? 'register' : 'login'), {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(register ? { email, password, fullName } : { email, password })
    });
    await enterPatient(account);
  });
});

async function enterPatient(account) {
  token = account.token;
  auth.reset();
  byId('account').hidden = true;
  byId('empty').hidden = true;
  await showPortal();
}

function clearShare() {
  clearInterval(shareTimer);
  shareExpires = undefined;
  sharedDocument = undefined;
  byId('share').hidden = true;
  byId('share-file').textContent = '';
  byId('pin').textContent = '';
  byId('expiry').textContent = '';
}

function expireShare() {
  if (shareExpires && Date.now() >= shareExpires) {
    clearShare();
    status('The displayed PIN expired. Generate a new PIN to share again.');
  }
}

document.addEventListener('visibilitychange', expireShare);

async function loadDocuments() {
  const session = token;
  const documents = await request('/documents');
  if (session !== token) return;
  byId('empty').hidden = documents.length > 0;
  byId('documents').replaceChildren(...documents.map(documentRow));
}

function documentRow({ id, fileName, uploadedAt }) {
  const row = document.createElement('li');
  row.className = 'row';
  const details = document.createElement('span');
  const time = document.createElement('time');
  time.dateTime = uploadedAt;
  time.textContent = new Date(uploadedAt).toLocaleString();
  details.append(fileName, time);
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = 'Generate PIN';
  button.setAttribute('aria-label', 'Generate PIN for ' + fileName);
  button.addEventListener('click', () => run(button, 'Generating…', async () => {
    const session = token;
    const current = Symbol();
    latestShare = current;
    const share = await request('/documents/' + encodeURIComponent(id) + '/share', { method: 'POST' });
    if (session !== token || current !== latestShare) return;
    clearShare();
    sharedDocument = id;
    shareExpires = Date.parse(share.expiresAt);
    byId('share-file').textContent = fileName;
    byId('pin').textContent = share.pin;
    byId('expiry').textContent = 'Expires ' + new Date(share.expiresAt).toLocaleString();
    byId('share').hidden = false;
    status('Clinic access PIN generated.');
    shareTimer = setInterval(expireShare, 1000);
    expireShare();
  }));
  const view = actionButton('View PDF', () => openPreview(id, fileName), fileName);
  const picker = document.createElement('input');
  picker.type = 'file';
  picker.accept = '.pdf,application/pdf';
  picker.hidden = true;
  const update = actionButton('Replace PDF', () => picker.click(), fileName);
  picker.addEventListener('change', () => {
    const file = picker.files[0];
    picker.value = '';
    if (!file || !confirm('Replace “' + fileName + '”? Its existing share PINs will stop working.')) return;
    run(update, 'Replacing…', async () => {
      const currentToken = token;
      await sendFile('/documents/' + id, 'PUT', file);
      if (currentToken !== token) return;
      if (sharedDocument === id) clearShare();
      await loadDocuments();
      if (currentToken === token) status('PDF replaced.');
    });
  });
  row.classList.add('document-row');
  row.append(details, actionMenu(fileName, view, update, button, deleteButton(id, fileName, false)), picker);
  return row;
}

upload.addEventListener('submit', event => {
  event.preventDefault();
  const file = upload.elements.file.files[0];
  if (!file || file.size > 10 * 1024 * 1024) {
    status('Choose a PDF up to 10 MB.', true);
    upload.elements.file.focus();
    return;
  }
  run(upload.querySelector('button'), 'Uploading…', async () => {
    const session = token;
    await request('/documents/upload', { method: 'POST', body: new FormData(upload) });
    if (session !== token) return;
    upload.reset();
    status('PDF uploaded.');
    await loadDocuments();
  });
});

let sharedDocument;
let portalRequest = 0;
const photoUrls = new Set();
let cameraStream;
let cameraRequest = 0;
let preview;
let printUrls = [];
let pdfLibrary;

function actionButton(label, action, fileName) {
  const button = document.createElement('button');
  button.type = 'button';
  button.textContent = label;
  if (fileName) button.setAttribute('aria-label', label + ' for ' + fileName);
  button.addEventListener('click', action);
  return button;
}

let openActionMenu;
function closeActionMenu(returnFocus = false) {
  const menu = openActionMenu;
  if (!menu) return;
  openActionMenu = undefined;
  menu.open = false;
  if (returnFocus) menu.querySelector('summary').focus();
}

function actionMenu(fileName, ...actions) {
  const menu = document.createElement('details');
  menu.className = 'record-actions';
  const trigger = document.createElement('summary');
  trigger.textContent = '⋯';
  trigger.setAttribute('aria-label', 'Actions for ' + fileName);
  const options = document.createElement('div');
  options.className = 'record-options';
  options.append(...actions);
  menu.append(trigger, options);
  trigger.addEventListener('click', () => {
    if (openActionMenu !== menu) closeActionMenu();
    openActionMenu = menu.open ? undefined : menu;
  });
  options.addEventListener('click', event => {
    if (event.target.closest('button')) closeActionMenu(true);
  }, { capture: true });
  return menu;
}

document.addEventListener('click', event => {
  if (openActionMenu && !openActionMenu.contains(event.target)) closeActionMenu();
});
document.addEventListener('keydown', event => {
  if (event.key === 'Escape' && openActionMenu) {
    event.preventDefault();
    closeActionMenu(true);
  }
});

async function sendFile(path, method, file) {
  if (file.size > 10 * 1024 * 1024) throw new Error('Choose a file up to 10 MB.');
  const body = new FormData();
  body.set('file', file);
  return request(path, { method, body });
}

function deleteButton(id, fileName, photo) {
  const button = actionButton(photo ? 'Delete photo' : 'Delete PDF', () => {
    if (!confirm('Delete “' + fileName + '”? This cannot be undone.')) return;
    run(button, 'Deleting…', async () => {
      const currentToken = token;
      await request('/documents/' + id, { method: 'DELETE' });
      if (currentToken !== token) return;
      if (sharedDocument === id) clearShare();
      if (photo) await loadGallery(portalRequest);
      else await loadDocuments();
      if (currentToken === token) status(photo ? 'Photo deleted.' : 'PDF deleted.');
    });
  }, fileName);
  return button;
}

function clearGallery() {
  photoObserver.disconnect();
  for (const url of photoUrls) URL.revokeObjectURL(url);
  photoUrls.clear();
  byId('photos').replaceChildren();
  byId('gallery-empty').hidden = true;
}

const photoObserver = new IntersectionObserver(entries => {
  for (const { target, isIntersecting } of entries) {
    if (!isIntersecting) continue;
    photoObserver.unobserve(target);
    const generation = portalRequest;
    request('/documents/' + target.dataset.photoId + '/content', {}, true)
      .then(response => response.blob())
      .then(blob => {
        if (generation !== portalRequest || !target.isConnected) return;
        const url = URL.createObjectURL(blob);
        photoUrls.add(url);
        target.src = url;
      })
      .catch(error => {
        if (generation === portalRequest) status(error.message, true);
      });
  }
});

async function loadGallery(generation) {
  const data = await request('/documents/photos');
  if (!token || generation !== portalRequest) return;
  clearGallery();
  byId('gallery-empty').hidden = data.length > 0;
  for (const { id, fileName } of data) {
    const item = document.createElement('li');
    const thumb = document.createElement('img');
    thumb.alt = '';
    thumb.dataset.photoId = id;
    thumb.addEventListener('error', () => {
      if (generation === portalRequest) status('Could not display ' + fileName + '.', true);
    });
    const heading = document.createElement('p');
    heading.textContent = fileName;
    item.append(thumb, heading, actionMenu(fileName,
      actionButton('View photo', () => openPreview(id, fileName, true), fileName), deleteButton(id, fileName, true)));
    byId('photos').append(item);
    photoObserver.observe(thumb);
  }
}

function stopCamera() {
  cameraRequest++;
  cameraStream?.getTracks().forEach(track => track.stop());
  cameraStream = undefined;
  byId('camera-preview').srcObject = null;
  byId('camera').hidden = true;
}

byId('start-camera').addEventListener('click', () => run(byId('start-camera'), 'Opening camera…', async () => {
  stopCamera();
  const generation = cameraRequest;
  if (!navigator.mediaDevices?.getUserMedia) throw new Error('Camera access requires HTTPS or localhost. You can also upload a photo.');
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: { ideal: 'environment' } } });
  } catch {
    if (generation === cameraRequest) throw new Error('Could not open the camera. Allow camera access or upload a photo.');
    return;
  }
  if (generation !== cameraRequest) {
    stream.getTracks().forEach(track => track.stop());
    return;
  }
  cameraStream = stream;
  byId('camera-preview').srcObject = stream;
  byId('camera').hidden = false;
}));
byId('stop-camera').addEventListener('click', stopCamera);

async function savePhoto(file) {
  const generation = portalRequest;
  await sendFile('/documents/photos', 'POST', file);
  if (!token || generation !== portalRequest) return;
  byId('photo-upload').reset();
  await loadGallery(generation);
  if (token && generation === portalRequest) status('Photo added to your gallery.');
}

byId('capture-photo').addEventListener('click', () => run(byId('capture-photo'), 'Saving photo…', async () => {
  const video = byId('camera-preview');
  if (!video.videoWidth) throw new Error('Wait until the camera preview is visible.');
  const snapshot = document.createElement('canvas');
  const scale = Math.min(1, 2400 / Math.max(video.videoWidth, video.videoHeight));
  snapshot.width = Math.round(video.videoWidth * scale);
  snapshot.height = Math.round(video.videoHeight * scale);
  snapshot.getContext('2d').drawImage(video, 0, 0, snapshot.width, snapshot.height);
  const generation = cameraRequest;
  const blob = await new Promise(resolve => snapshot.toBlob(resolve, 'image/jpeg', .9));
  snapshot.width = snapshot.height = 0;
  if (!token || generation !== cameraRequest) return;
  stopCamera();
  if (!blob) throw new Error('Could not capture the photo.');
  await savePhoto(new File([blob], 'Camera-' + Date.now() + '.jpg', { type: 'image/jpeg' }));
}));

const photoForm = byId('photo-upload');
photoForm.addEventListener('submit', event => {
  event.preventDefault();
  run(photoForm.querySelector('button'), 'Uploading…', () => savePhoto(photoForm.elements.file.files[0]));
});

const profileForm = byId('profile-form');
async function loadProfile(generation) {
  const profile = await request('/profile');
  if (!token || generation !== portalRequest) return;
  for (const [key, value] of Object.entries(profile)) profileForm.elements[key].value = value ?? '';
}

profileForm.addEventListener('submit', event => {
  event.preventDefault();
  const data = Object.fromEntries(new FormData(profileForm));
  data.dateOfBirth ||= null;
  run(profileForm.querySelector('button'), 'Saving…', async () => {
    const generation = portalRequest;
    const saved = await request('/profile', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data)
    });
    if (!token || generation !== portalRequest) return;
    for (const [key, value] of Object.entries(saved)) profileForm.elements[key].value = value ?? '';
    status('Profile saved.');
  });
});

async function showPortal() {
  if (!token) return;
  const generation = ++portalRequest;
  const page = ['#profile', '#gallery'].includes(location.hash) ? location.hash.slice(1) : 'vault';
  stopCamera();
  clearGallery();
  closePreview();
  for (const id of ['vault', 'profile', 'gallery']) byId(id).hidden = id !== page;
  byId('portal-back').hidden = page === 'vault';
  status('');
  if (page === 'gallery') await loadGallery(generation);
  else if (page === 'profile') await loadProfile(generation);
  else await loadDocuments();
}
window.addEventListener('hashchange', () => showPortal().catch(error => status(error.message, true)));
window.addEventListener('pagehide', () => {
  portalRequest++;
  stopCamera();
  clearGallery();
  closePreview();
});
window.addEventListener('pageshow', event => {
  if (!event.persisted || !token || byId('gallery').hidden) return;
  const generation = ++portalRequest;
  loadGallery(generation).catch(error => {
    if (generation === portalRequest) status(error.message, true);
  });
});

const documentDialog = byId('document-dialog');
const documentCanvas = byId('document-canvas');
const pdfRoot = 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/';

function clearPrint() {
  document.body.classList.remove('printing');
  byId('print-pages').replaceChildren();
  for (const url of printUrls) URL.revokeObjectURL(url);
  printUrls = [];
}

function closePreview() {
  const closing = preview;
  preview = undefined;
  if (documentDialog.open) documentDialog.close();
  closing?.controller.abort();
  closing?.render?.cancel();
  closing?.printRender?.cancel();
  closing?.loading?.destroy().catch(() => {});
  if (closing?.photoUrl) URL.revokeObjectURL(closing.photoUrl);
  byId('document-photo').removeAttribute('src');
  byId('document-photo').hidden = true;
  documentCanvas.width = documentCanvas.height = 0;
  documentCanvas.hidden = true;
  byId('document-toolbar').hidden = true;
  byId('document-title').textContent = '';
  byId('document-pages').textContent = '';
  byId('document-status').textContent = '';
  clearPrint();
}

async function openPreview(id, title, photo = false) {
  closePreview();
  const current = { controller: new AbortController(), page: 1 };
  preview = current;
  byId('document-title').textContent = title;
  byId('document-back').textContent = photo ? 'Back to gallery' : 'Back to vault';
  byId('document-status').textContent = 'Opening…';
  documentDialog.showModal();
  try {
    const response = await request('/documents/' + id + '/content', { signal: current.controller.signal }, true);
    if (photo) {
      const blob = await response.blob();
      if (preview !== current) return;
      current.photoUrl = URL.createObjectURL(blob);
      const image = byId('document-photo');
      image.alt = title;
      image.src = current.photoUrl;
      await image.decode();
      if (preview !== current) return;
      image.hidden = false;
    } else {
      pdfLibrary ??= await import(pdfRoot + 'build/pdf.mjs');
      const bytes = new Uint8Array(await response.arrayBuffer());
      if (preview !== current) {
        bytes.fill(0);
        return;
      }
      pdfLibrary.GlobalWorkerOptions.workerSrc = pdfRoot + 'build/pdf.worker.mjs';
      current.loading = pdfLibrary.getDocument({
        data: bytes, isEvalSupported: false, cMapUrl: pdfRoot + 'cmaps/',
        standardFontDataUrl: pdfRoot + 'standard_fonts/'
      });
      current.pdf = await current.loading.promise;
      if (preview !== current) return;
      await renderPreview(current, 1);
      if (preview !== current) return;
      documentCanvas.hidden = false;
      byId('document-toolbar').hidden = false;
    }
    byId('document-status').textContent = '';
  } catch (error) {
    if (preview === current) byId('document-status').textContent = error.message;
  }
}

function renderCanvas(page, canvas, maxSize) {
  const natural = page.getViewport({ scale: 1 });
  const viewport = page.getViewport({ scale: maxSize / Math.max(natural.width, natural.height) });
  canvas.width = Math.ceil(viewport.width);
  canvas.height = Math.ceil(viewport.height);
  return page.render({ canvasContext: canvas.getContext('2d'), viewport });
}

async function renderPreview(current, number) {
  if (current.busy || number < 1 || number > current.pdf.numPages) return;
  current.busy = true;
  byId('document-previous').disabled = byId('document-next').disabled = true;
  try {
    const page = await current.pdf.getPage(number);
    if (preview !== current) return;
    current.render = renderCanvas(page, documentCanvas, 1500);
    await current.render.promise;
    if (preview !== current) return;
    current.page = number;
    byId('document-pages').textContent = number + ' / ' + current.pdf.numPages;
    documentCanvas.setAttribute('aria-label', 'PDF page ' + number);
  } finally {
    current.busy = false;
    current.render = undefined;
    if (preview === current) {
      byId('document-previous').disabled = current.page === 1;
      byId('document-next').disabled = current.page === current.pdf.numPages;
    }
  }
}

for (const [id, step] of [['document-previous', -1], ['document-next', 1]]) {
  byId(id).addEventListener('click', () => {
    const current = preview;
    if (current?.pdf) renderPreview(current, current.page + step).catch(error => {
      if (current === preview) byId('document-status').textContent = error.message;
    });
  });
}

byId('document-print').addEventListener('click', async () => {
  const current = preview;
  if (!current?.pdf) return;
  const button = byId('document-print');
  button.disabled = true;
  try {
    clearPrint();
    byId('document-status').textContent = 'Preparing PDF for printing…';
    for (let number = 1; number <= current.pdf.numPages; number++) {
      const page = await current.pdf.getPage(number);
      if (preview !== current) return;
      const canvas = document.createElement('canvas');
      current.printRender = renderCanvas(page, canvas, 1700);
      await current.printRender.promise;
      const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/png'));
      canvas.width = canvas.height = 0;
      if (preview !== current) return;
      if (!blob) throw new Error('Could not prepare this PDF for printing.');
      const url = URL.createObjectURL(blob);
      printUrls.push(url);
      const image = document.createElement('img');
      image.src = url;
      await image.decode();
      if (preview !== current) return;
      byId('print-pages').append(image);
    }
    if (preview !== current) return;
    document.body.classList.add('printing');
    byId('document-status').textContent = '';
    window.print();
  } catch (error) {
    if (preview === current) {
      clearPrint();
      byId('document-status').textContent = error.message;
    }
  } finally {
    button.disabled = false;
  }
});
window.addEventListener('afterprint', clearPrint);
byId('document-back').addEventListener('click', closePreview);
documentDialog.addEventListener('cancel', event => {
  event.preventDefault();
  closePreview();
});
setupPasswordReset(byId('forgot'), auth.elements.email, status);
setupSocial({ doctor: false, getToken: () => token, enter: enterPatient, notify: status });
