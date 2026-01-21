// Content script for Cookie Injector

// Inject the main script into page context
const script = document.createElement('script');
script.src = chrome.runtime.getURL('injected.js');
script.onload = function() {
    this.remove();
};
(document.head || document.documentElement).appendChild(script);

// Listen for messages from injected script
window.addEventListener('message', (event) => {
    if (event.source !== window) return;
    
    if (event.data.type === 'COOKIE_INJECTOR_TO_BACKGROUND') {
        chrome.runtime.sendMessage(event.data.payload, (response) => {
            window.postMessage({
                type: 'COOKIE_INJECTOR_FROM_BACKGROUND',
                payload: response
            }, '*');
        });
    }
});

// Apply zoom level
chrome.storage.local.get(['zoomLevel'], (result) => {
    const zoomLevel = result.zoomLevel || 1.0;
    applyZoom(zoomLevel);
});

// Listen for zoom changes
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'applyZoom') {
        applyZoom(request.level);
        sendResponse({ success: true });
    }
});

function applyZoom(level) {
    // Apply CSS zoom
    document.documentElement.style.zoom = level;
    
    // Also set viewport meta for better control
    let viewport = document.querySelector('meta[name="viewport"]');
    if (!viewport) {
        viewport = document.createElement('meta');
        viewport.name = 'viewport';
        document.head.appendChild(viewport);
    }
    
    const scale = level;
    viewport.content = `width=device-width, initial-scale=${scale}, maximum-scale=${scale}, user-scalable=yes`;
}

console.log('Cookie Injector content script loaded');
