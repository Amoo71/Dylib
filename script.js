const urlForm = document.getElementById("urlForm");
const urlInput = document.getElementById("urlInput");
const browserView = document.getElementById("browserView");
const optionsToggle = document.getElementById("optionsToggle");
const optionSheet = document.getElementById("optionSheet");
const adBlocker = document.getElementById("adBlocker");
const antiNewTab = document.getElementById("antiNewTab");
const proxySwitch = document.getElementById("proxySwitch");
const proxyList = document.getElementById("proxyList");
const proxyTest = document.getElementById("proxyTest");
const sheetMessage = document.getElementById("sheetMessage");
const devtoolsToggle = document.getElementById("devtoolsToggle");
const networkPanel = document.getElementById("networkPanel");
const networkList = document.getElementById("networkList");
const startRecord = document.getElementById("startRecord");
const stopRecord = document.getElementById("stopRecord");
const clearRecord = document.getElementById("clearRecord");
const activeStatus = document.getElementById("activeStatus");
const pipToggle = document.getElementById("pipToggle");
const mockVideo = document.getElementById("mockVideo");

let recording = true;
let currentProxyIndex = 0;
let blockedPopups = 0;

function normalizeUrl(value) {
  if (!value) return "https://example.com";
  const hasProtocol = /^https?:\/\//i.test(value);
  return hasProtocol ? value : `https://${value}`;
}

function logNetwork(url, result = "ok") {
  if (!recording) return;
  const item = document.createElement("li");
  const stamp = new Date().toLocaleTimeString("de-DE", { hour12: false });
  item.textContent = `${stamp} • ${url} • ${result}`;
  if (result !== "ok") item.classList.add("fail");
  networkList.prepend(item);
}

function setStatus(text) {
  activeStatus.textContent = text;
}

urlForm.addEventListener("submit", (event) => {
  event.preventDefault();
  const target = normalizeUrl(urlInput.value.trim());

  if (adBlocker.checked && /(doubleclick|ads|tracker|popunder)/i.test(target)) {
    setStatus("Geblockt: Ad/Tracker URL");
    logNetwork(target, "blocked-by-adblock");
    return;
  }

  browserView.src = target;
  setStatus(`Geladen: ${target}`);
  logNetwork(target);
});

optionsToggle.addEventListener("click", () => {
  optionSheet.hidden = !optionSheet.hidden;
});

devtoolsToggle.addEventListener("change", () => {
  networkPanel.hidden = !devtoolsToggle.checked;
});

startRecord.addEventListener("click", () => {
  recording = true;
  setStatus("Recording aktiv");
});

stopRecord.addEventListener("click", () => {
  recording = false;
  setStatus("Recording pausiert");
});

clearRecord.addEventListener("click", () => {
  networkList.innerHTML = "";
  setStatus("Logs geleert");
});

proxyTest.addEventListener("click", () => {
  const proxies = proxyList.value
    .split("\n")
    .map((entry) => entry.trim())
    .filter(Boolean);

  if (!proxySwitch.checked) {
    sheetMessage.textContent = "Proxy Auto Switch ist deaktiviert.";
    return;
  }

  if (!proxies.length) {
    sheetMessage.textContent = "Keine Proxy-Einträge vorhanden.";
    return;
  }

  currentProxyIndex = (currentProxyIndex + 1) % proxies.length;
  const activeProxy = proxies[currentProxyIndex];
  sheetMessage.textContent = `Aktiver Proxy: ${activeProxy}`;
  setStatus(`Proxy gewechselt (${currentProxyIndex + 1}/${proxies.length})`);
  logNetwork(activeProxy, "proxy-switch");
});

window.open = new Proxy(window.open, {
  apply(target, thisArg, args) {
    if (antiNewTab.checked) {
      blockedPopups += 1;
      setStatus(`Neuer Tab blockiert (${blockedPopups})`);
      logNetwork(String(args?.[0] || "popup"), "blocked-popup");
      return null;
    }
    return Reflect.apply(target, thisArg, args);
  }
});

pipToggle.addEventListener("click", async () => {
  const videoVisible = mockVideo.classList.toggle("active");
  if (videoVisible) {
    try {
      await mockVideo.play();
      if (document.pictureInPictureEnabled && !document.pictureInPictureElement) {
        await mockVideo.requestPictureInPicture();
      }
      setStatus("iOS Player + PiP aktiv");
      logNetwork("pip://video", "pip-enabled");
    } catch {
      setStatus("PiP nicht verfügbar, Inline-Player aktiv");
      logNetwork("pip://video", "pip-fallback");
    }
  } else {
    mockVideo.pause();
    if (document.pictureInPictureElement) {
      await document.exitPictureInPicture();
    }
    setStatus("PiP deaktiviert");
  }
});

setStatus("Bereit");
networkPanel.hidden = !devtoolsToggle.checked;
logNetwork(browserView.src);
