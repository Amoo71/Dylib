// Popup script for Cookie Injector

let currentZoomLevel = 1.0;
let sessions = [];

// Initialize popup
document.addEventListener('DOMContentLoaded', async () => {
    // Load settings
    const settings = await chrome.runtime.sendMessage({ action: 'getSettings' });
    
    // Set macOS toggle
    const macosToggle = document.getElementById('macosToggle');
    macosToggle.checked = settings.macOSEmulation || false;
    
    // Set zoom level
    currentZoomLevel = settings.zoomLevel || 1.0;
    updateZoomDisplay();
    
    // Load sessions from justpaste.it
    await loadSessions();
    
    // Setup event listeners
    setupEventListeners();
});

function setupEventListeners() {
    // macOS toggle
    document.getElementById('macosToggle').addEventListener('change', async (e) => {
        await chrome.runtime.sendMessage({
            action: 'toggleMacOS',
            enabled: e.target.checked
        });
        
        // Reload active tab to apply changes
        const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
        if (tabs.length > 0) {
            chrome.tabs.reload(tabs[0].id);
        }
    });
    
    // Zoom controls
    document.getElementById('zoomIn').addEventListener('click', async () => {
        currentZoomLevel = Math.min(currentZoomLevel + 0.1, 2.0);
        await updateZoom();
    });
    
    document.getElementById('zoomOut').addEventListener('click', async () => {
        currentZoomLevel = Math.max(currentZoomLevel - 0.1, 0.5);
        await updateZoom();
    });
    
    // Inject button
    document.getElementById('injectBtn').addEventListener('click', handleInject);
}

async function updateZoom() {
    await chrome.runtime.sendMessage({
        action: 'setZoom',
        level: currentZoomLevel
    });
    
    updateZoomDisplay();
    
    // Apply to active tab
    const tabs = await chrome.tabs.query({ active: true, currentWindow: true });
    if (tabs.length > 0) {
        await chrome.tabs.sendMessage(tabs[0].id, {
            action: 'applyZoom',
            level: currentZoomLevel
        }).catch(() => {
            // Tab might not have content script yet, reload it
            chrome.tabs.reload(tabs[0].id);
        });
    }
}

function updateZoomDisplay() {
    const percent = Math.round(currentZoomLevel * 100);
    document.getElementById('zoomLevel').textContent = `${percent}%`;
}

async function loadSessions() {
    const statusDiv = document.getElementById('status');
    const selectElement = document.getElementById('sessionSelect');
    const injectBtn = document.getElementById('injectBtn');
    
    try {
        showStatus('Loading sessions from justpaste.it...', 'loading');
        
        // Fetch the page
        const response = await fetch('https://justpaste.it/a7vyr');
        if (!response.ok) {
            throw new Error('Failed to fetch sessions');
        }
        
        const html = await response.text();
        
        // Parse sessions from HTML
        sessions = parseSessionsFromHTML(html);
        
        if (sessions.length === 0) {
            throw new Error('No sessions found');
        }
        
        // Populate dropdown
        selectElement.innerHTML = '<option value="">Select a session...</option>';
        sessions.forEach((session, index) => {
            const option = document.createElement('option');
            option.value = index;
            option.textContent = session.name;
            selectElement.appendChild(option);
        });
        
        injectBtn.disabled = false;
        showStatus(`Found ${sessions.length} session(s)`, 'success');
        
        // Hide status after 2 seconds
        setTimeout(() => {
            hideStatus();
        }, 2000);
        
    } catch (error) {
        console.error('Failed to load sessions:', error);
        selectElement.innerHTML = '<option value="">Failed to load sessions</option>';
        showStatus('Error: ' + error.message, 'error');
    }
}

function parseSessionsFromHTML(html) {
    const parsedSessions = [];
    
    try {
        // Look for session patterns in the HTML
        // Format: sess:"name=value;name2=value2"
        // Or multiline format with session labels
        
        // Try to extract from textarea or pre tags
        const textareaMatch = html.match(/<textarea[^>]*>([\s\S]*?)<\/textarea>/i);
        const preMatch = html.match(/<pre[^>]*>([\s\S]*?)<\/pre>/i);
        
        let content = '';
        if (textareaMatch) {
            content = textareaMatch[1];
        } else if (preMatch) {
            content = preMatch[1];
        } else {
            // Try to find in article body
            const articleMatch = html.match(/<article[^>]*>([\s\S]*?)<\/article>/i);
            if (articleMatch) {
                content = articleMatch[1];
            }
        }
        
        // Decode HTML entities
        content = decodeHTMLEntities(content);
        
        // Parse sessions
        // Pattern 1: sess:"cookies" or session 1:"cookies"
        const sessPattern1 = /sess(?:ion)?\s*(\d+)?[:\s]*["']([^"']+)["']/gi;
        let match;
        
        while ((match = sessPattern1.exec(content)) !== null) {
            const sessionNum = match[1] || (parsedSessions.length + 1);
            const cookies = match[2];
            
            parsedSessions.push({
                name: `Session ${sessionNum}`,
                cookies: cookies
            });
        }
        
        // Pattern 2: Line-based format
        // Session 1
        // cookie1=value1;cookie2=value2
        if (parsedSessions.length === 0) {
            const lines = content.split('\n');
            let currentSession = null;
            
            for (let i = 0; i < lines.length; i++) {
                const line = lines[i].trim();
                
                // Check if it's a session label
                const sessionMatch = line.match(/session\s*(\d+)/i);
                if (sessionMatch) {
                    currentSession = sessionMatch[1];
                } else if (currentSession && line.includes('=') && line.length > 10) {
                    // This looks like cookies
                    parsedSessions.push({
                        name: `Session ${currentSession}`,
                        cookies: line
                    });
                    currentSession = null;
                }
            }
        }
        
        // Pattern 3: Simple numbered list with cookies
        if (parsedSessions.length === 0) {
            const cookieLines = content.split('\n').filter(line => {
                const trimmed = line.trim();
                return trimmed.includes('=') && trimmed.includes(';') && trimmed.length > 20;
            });
            
            cookieLines.forEach((line, index) => {
                parsedSessions.push({
                    name: `Session ${index + 1}`,
                    cookies: line.trim()
                });
            });
        }
        
    } catch (error) {
        console.error('Error parsing sessions:', error);
    }
    
    return parsedSessions;
}

function decodeHTMLEntities(text) {
    const textarea = document.createElement('textarea');
    textarea.innerHTML = text;
    return textarea.value;
}

async function handleInject() {
    const selectElement = document.getElementById('sessionSelect');
    const selectedIndex = selectElement.value;
    
    if (!selectedIndex || selectedIndex === '') {
        showStatus('Please select a session', 'error');
        return;
    }
    
    const session = sessions[selectedIndex];
    if (!session) {
        showStatus('Invalid session', 'error');
        return;
    }
    
    const injectBtn = document.getElementById('injectBtn');
    injectBtn.disabled = true;
    
    try {
        showStatus('Injecting cookies...', 'loading');
        
        // Send to background script
        const response = await chrome.runtime.sendMessage({
            action: 'injectCookies',
            sessionData: session.cookies
        });
        
        if (response.success) {
            showStatus(`Success! Injected ${response.cookiesInjected} cookies. Opening Netflix...`, 'success');
        } else {
            throw new Error(response.error || 'Unknown error');
        }
        
    } catch (error) {
        console.error('Injection failed:', error);
        showStatus('Error: ' + error.message, 'error');
        injectBtn.disabled = false;
    }
}

function showStatus(message, type) {
    const statusDiv = document.getElementById('status');
    statusDiv.textContent = message;
    statusDiv.className = `status ${type}`;
}

function hideStatus() {
    const statusDiv = document.getElementById('status');
    statusDiv.className = 'status';
}
