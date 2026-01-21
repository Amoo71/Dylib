// Injected script running in page context
// This has access to the page's JavaScript context

(function() {
    'use strict';
    
    console.log('Cookie Injector injected script loaded');
    
    // Override navigator properties for macOS emulation
    function emulateMacOS() {
        // Override platform
        Object.defineProperty(navigator, 'platform', {
            get: () => 'MacIntel'
        });
        
        // Override userAgent
        Object.defineProperty(navigator, 'userAgent', {
            get: () => 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.2 Safari/605.1.15'
        });
        
        // Override vendor
        Object.defineProperty(navigator, 'vendor', {
            get: () => 'Apple Computer, Inc.'
        });
        
        // Override maxTouchPoints
        Object.defineProperty(navigator, 'maxTouchPoints', {
            get: () => 0
        });
    }
    
    // Check if macOS emulation is enabled
    window.addEventListener('message', (event) => {
        if (event.source !== window) return;
        
        if (event.data.type === 'ENABLE_MACOS_EMULATION') {
            emulateMacOS();
        }
    });
    
    // Apply macOS emulation immediately if enabled
    // This will be controlled by the content script checking storage
    
})();
