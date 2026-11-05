"""Native browser dialog for the Local update reminder (no external scripts)."""
from __future__ import annotations

import uuid

from .i18n import t, current
from .updates import download_page_url


DIALOG_TEMPLATE = """
<dialog class="update-dialog" aria-labelledby="update-dialog-title">
  <div class="dialog-heading"><h2 id="update-dialog-title"></h2><button type="button" class="close-dialog">×</button></div>
  <p class="current-version"></p><p class="latest-version"></p><p class="release-date"></p>
  <h3 class="notes-title"></h3><div class="release-notes" tabindex="0"><ul></ul></div>
  <p class="save-error" role="alert"></p>
  <div class="dialog-actions">
    <button type="button" class="ignore-version"></button>
    <button type="button" class="dismiss-update"></button>
    <a class="download-update"></a>
  </div>
</dialog>
"""

DIALOG_CSS = """
:host { display: contents; }
.update-dialog { box-sizing: border-box; width: min(560px, calc(100vw - 32px)); max-height: calc(100vh - 40px); overflow: auto; border: 1px solid #d9e4ee; border-radius: 18px; padding: 24px; color: #132943; background: #fff; box-shadow: 0 24px 80px #10263d40; font-family: inherit; }
.update-dialog::backdrop { background: #10263d70; }
.dialog-heading { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
h2 { margin: 0; color: #173a61; font-size: 1.35rem; }
h3 { margin: 20px 0 8px; font-size: 1rem; }
p { line-height: 1.6; margin: 10px 0; }
.latest-version { font-weight: 700; color: #216ca6; }
.release-notes { max-height: min(40vh, 300px); overflow: auto; line-height: 1.65; }
ul { margin: 0; padding-left: 22px; } li { margin: 6px 0; white-space: pre-wrap; overflow-wrap: anywhere; }
.dialog-actions { display: flex; justify-content: flex-end; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 24px; }
button, a { box-sizing: border-box; font: inherit; border-radius: 9px; padding: 10px 12px; cursor: pointer; min-height: 44px; border: 1px solid #d9e4ee; background: #fff; color: #173a61; text-decoration: none; }
.close-dialog { border: 0; font-size: 1.5rem; padding: 4px 10px; }
.ignore-version { border-color: transparent; color: #64758a; margin-right: auto; }
.download-update { background: #216ca6; color: #fff; border-color: #216ca6; }
button:disabled { opacity: .55; cursor: wait; }
button:focus-visible, a:focus-visible, .release-notes:focus-visible { outline: 3px solid #37a7c9; outline-offset: 2px; }
.save-error { color: #ad3939; font-size: .9rem; }
[hidden] { display: none !important; }
"""

DIALOG_JS = """
let dialog, closeButton, later, ignore, download, error;
let shown = null;
async function remember(action, keepalive=false) {
  const response = await fetch('/api/updates/reminder', {
    method: 'POST', headers: {'Content-Type':'application/json','X-FGO-Control':'1'},
    body: JSON.stringify({versionCode: shown.release.versionCode, action}), keepalive
  });
  if (!response.ok) throw new Error('reminder');
}
function dismiss() {
  if (!shown) return;
  remember('dismiss', true).catch(() => {});
  dialog.close();
}
function bindDialog() {
  const current = element.querySelector('dialog');
  if (current === dialog) return;
  dialog = current;
  closeButton = element.querySelector('.close-dialog');
  later = element.querySelector('.dismiss-update');
  ignore = element.querySelector('.ignore-version');
  download = element.querySelector('.download-update');
  error = element.querySelector('.save-error');
  closeButton.addEventListener('click', dismiss);
  later.addEventListener('click', dismiss);
  dialog.addEventListener('cancel', (event) => { event.preventDefault(); dismiss(); });
  dialog.addEventListener('click', (event) => {
    if (event.target !== dialog) return;
    const rect = dialog.getBoundingClientRect();
    if (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) dismiss();
  });
  ignore.addEventListener('click', async () => {
    ignore.disabled = true; error.textContent = '';
    try { await remember('ignore'); dialog.close(); }
    catch (_) { error.textContent = shown.labels.saveFailed; }
    finally { ignore.disabled = false; }
  });
  download.addEventListener('click', () => { remember('dismiss', true).catch(() => {}); dialog.close(); });
}
function render() {
  bindDialog();
  const value = props.value;
  if (!value || !value.showPrompt || !value.release) return;
  shown = value;
  const labels = value.labels;
  element.querySelector('#update-dialog-title').textContent = labels.title;
  closeButton.setAttribute('aria-label', labels.close);
  element.querySelector('.current-version').textContent = labels.current;
  element.querySelector('.latest-version').textContent = labels.latest;
  const date = element.querySelector('.release-date');
  date.hidden = !labels.date; date.textContent = labels.date;
  element.querySelector('.notes-title').textContent = labels.notes;
  const notes = element.querySelector('ul'); notes.replaceChildren();
  for (const note of value.notes) { const li=document.createElement('li'); li.textContent=note; notes.appendChild(li); }
  ignore.hidden = value.source !== 'passive'; ignore.textContent = labels.ignore;
  later.textContent = labels.later; download.textContent = labels.download;
  download.href = value.downloadPageUrl; download.target = '_self';
  error.textContent = '';
  if (!dialog.open) { dialog.showModal(); later.focus(); }
}
watch('value', render);
render();
"""


def dialog_value(status: dict) -> dict:
    if not status.get("showPrompt") or not status.get("release"):
        return {"showPrompt": False}
    release = status["release"]
    localized = release.get("changelogI18n", {})
    notes = localized.get(current()) or localized.get("zh-Hans") or release.get("changelog") or [t("update.notesEmpty")]
    date = release.get("releaseDate", "").split("T")[0]
    return {"showPrompt": True, "requestId": uuid.uuid4().hex, "source": status["source"],
            "release": {"versionCode": release["versionCode"]}, "notes": notes,
            "downloadPageUrl": download_page_url(),
            "labels": {"title": t("update.dialogTitle"), "close": t("update.close"),
                       "current": t("update.current", name=status["current"]["versionName"], code=status["current"]["versionCode"]),
                       "latest": t("update.available", name=release["versionName"]),
                       "date": t("update.releaseDate", date=date) if date else "",
                       "notes": t("update.notes"), "ignore": t("update.ignore"),
                       "later": t("update.later"), "download": t("update.download"),
                       "saveFailed": t("update.saveFailed")}}
