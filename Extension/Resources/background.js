// Background service worker for Cookie Injector extension

let macOSEmulationEnabled = false;
let currentZoomLevel = 1.0;

// Load settings on startup
chrome.storage.local.get(['macOSEmulation', 'zoomLevel'], (result) => {
    macOSEmulationEnabled = result.macOSEmulation || false;
    currentZoomLevel = result.zoomLevel || 1.0;
});

// Listen for messages from popup and content scripts
chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
    if (request.action === 'toggleMacOS') {
        macOSEmulationEnabled = request.enabled;
        chrome.storage.local.set({ macOSEmulation: macOSEmulationEnabled });
        sendResponse({ success: true });
    } else if (request.action === 'setZoom') {
        currentZoomLevel = request.level;
        chrome.storage.local.set({ zoomLevel: currentZoomLevel });
        sendResponse({ success: true });
    } else if (request.action === 'getSettings') {
        sendResponse({
            macOSEmulation: macOSEmulationEnabled,
            zoomLevel: currentZoomLevel
        });
    } else if (request.action === 'injectCookies') {
        handleCookieInjection(request.sessionData, sendResponse);
        return true; // Keep channel open for async response
    }
});

// Modify User-Agent header for macOS emulation
chrome.webRequest.onBeforeSendHeaders.addListener(
    (details) => {
        if (macOSEmulationEnabled) {
            let headers = details.requestHeaders;
            for (let i = 0; i < headers.length; i++) {
                if (headers[i].name.toLowerCase() === 'user-agent') {
                    headers[i].value = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15';
                }
            }
            return { requestHeaders: headers };
        }
    },
    { urls: ['<all_urls>'] },
    ['blocking', 'requestHeaders']
);

// Cookie injection handler
async function handleCookieInjection(sessionData, sendResponse) {
    try {
        console.log('Starting cookie injection:', sessionData);
        
        // Parse cookies from session data
        const cookies = parseCookies(sessionData);
        
        if (cookies.length === 0) {
            sendResponse({ success: false, error: 'No cookies found' });
            return;
        }
        
        // Get current tab
        const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
        if (tabs.length === 0) {
            sendResponse({ success: false, error: 'No active tab' });
            return;
        }
        
        const tabId = tabs[0].id;
        
        // Navigate to Netflix
        await chrome.tabs.update(tabId, { url: 'https://www.netflix.com' });
        
        // Wait for navigation to start
        await new Promise(resolve => setTimeout(resolve, 500));
        
        // Inject cookies
        for (const cookie of cookies) {
            try {
                await chrome.cookies.set({
                    url: 'https://www.netflix.com',
                    name: cookie.name,
                    value: cookie.value,
                    domain: cookie.domain || '.netflix.com',
                    path: cookie.path || '/',
                    secure: cookie.secure !== false,
                    httpOnly: cookie.httpOnly || false,
                    sameSite: cookie.sameSite || 'no_restriction',
                    expirationDate: cookie.expirationDate || (Date.now() / 1000) + (365 * 24 * 60 * 60)
                });
                console.log('Injected cookie:', cookie.name);
            } catch (err) {
                console.error('Failed to inject cookie:', cookie.name, err);
            }
        }
        
        // Wait a moment then reload
        await new Promise(resolve => setTimeout(resolve, 1000));
        await chrome.tabs.reload(tabId);
        
        sendResponse({ success: true, cookiesInjected: cookies.length });
        
    } catch (error) {
        console.error('Cookie injection failed:', error);
        sendResponse({ success: false, error: error.message });
    }
}

// Parse cookie string from session data
function parseCookies(sessionData) {
    const cookies = [];
    
    if (!sessionData || typeof sessionData !== 'string') {
        return cookies;
    }
    
    // Split by semicolon and parse each cookie
    const cookiePairs = sessionData.split(';');
    
    for (const pair of cookiePairs) {
        const trimmed = pair.trim();
        if (!trimmed) continue;
        
        const equalIndex = trimmed.indexOf('=');
        if (equalIndex === -1) continue;
        
        const name = trimmed.substring(0, equalIndex).trim();
        const value = trimmed.substring(equalIndex + 1).trim();
        
        if (name && value) {
            cookies.push({
                name: name,
                value: value,
                domain: '.netflix.com',
                path: '/',
                secure: true,
                httpOnly: false,
                sameSite: 'no_restriction'
            });
        }
    }
    
    return cookies;
}

console.log('Cookie Injector background script loaded');
