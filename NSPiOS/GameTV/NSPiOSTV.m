// NSPiOSTV: wird von NSPiOS in jede Spiel-App gelegt und dort mitgeladen (LC_LOAD_WEAK_DYLIB, GameLibrary.install).
//
// Spielt das Spiel auf einem Fernseher (AirPlay oder Kabel: die Shell zeigt es dort als eigene Szene) und ist ein
// Controller verbunden, braucht das iPhone nichts mehr zu zeigen: ein schwarzes Fenster liegt über allem. Auf OLED sind
// schwarze Pixel aus – praktisch „Bildschirm aus“, ohne dass iOS das Spiel anhält (Sperren würde es pausieren).
// Doppeltippen zeigt das Bild für 10 Sekunden (z. B. für das Zahnrad-Menü). NSPIOS_TV_BLACK=0 in Documents/env.txt schaltet es ab.
#import <UIKit/UIKit.h>
#import <GameController/GameController.h>

static UIWindow *g_black;
static NSTimer *g_peekTimer;
static BOOL g_peek;

static BOOL nspios_enabled(void) {
    NSString *path = [NSHomeDirectory() stringByAppendingPathComponent:@"Documents/env.txt"];
    NSString *text = [NSString stringWithContentsOfFile:path encoding:NSUTF8StringEncoding error:nil];
    for (NSString *line in [text componentsSeparatedByCharactersInSet:NSCharacterSet.newlineCharacterSet]) {
        NSString *l = [line stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet];
        if ([l hasPrefix:@"NSPIOS_TV_BLACK="]) return ![[l substringFromIndex:16] isEqualToString:@"0"];
    }
    return YES;
}

static BOOL nspios_tv_connected(void) {
    for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
        if ([scene.session.role isEqualToString:UIWindowSceneSessionRoleExternalDisplayNonInteractive]) return YES;
    }
    return NO;
}

static BOOL nspios_controller_connected(void) {
    for (GCController *c in GCController.controllers) {
        if (c.extendedGamepad || c.microGamepad) return YES;
    }
    return NO;
}

static UIWindowScene *nspios_phone_scene(void) {
    for (UIScene *scene in UIApplication.sharedApplication.connectedScenes) {
        if ([scene isKindOfClass:UIWindowScene.class] &&
            [scene.session.role isEqualToString:UIWindowSceneSessionRoleApplication]) return (UIWindowScene *)scene;
    }
    return nil;
}

static void nspios_update(void);

@interface NSPiOSBlackController : UIViewController
@end

@implementation NSPiOSBlackController
- (void)viewDidLoad {
    [super viewDidLoad];
    self.view.backgroundColor = UIColor.blackColor;
    UILabel *hint = [UILabel new];
    hint.text = @"TV · Double-tap to show the screen";
    hint.textColor = [UIColor colorWithWhite:1 alpha:0.35];
    hint.font = [UIFont systemFontOfSize:13 weight:UIFontWeightMedium];
    hint.translatesAutoresizingMaskIntoConstraints = NO;
    [self.view addSubview:hint];
    [NSLayoutConstraint activateConstraints:@[
        [hint.centerXAnchor constraintEqualToAnchor:self.view.centerXAnchor],
        [hint.centerYAnchor constraintEqualToAnchor:self.view.centerYAnchor],
    ]];
    // Hinweis nach 3 Sekunden ausblenden: danach ist der Bildschirm ganz schwarz
    [UIView animateWithDuration:1.0 delay:3.0 options:0 animations:^{ hint.alpha = 0; } completion:nil];
    UITapGestureRecognizer *tap = [[UITapGestureRecognizer alloc] initWithTarget:self action:@selector(peek)];
    tap.numberOfTapsRequired = 2;
    [self.view addGestureRecognizer:tap];
}
- (BOOL)prefersStatusBarHidden { return YES; }
- (BOOL)prefersHomeIndicatorAutoHidden { return YES; }
- (void)peek {
    g_peek = YES;
    nspios_update();
    [g_peekTimer invalidate];
    g_peekTimer = [NSTimer scheduledTimerWithTimeInterval:10 repeats:NO block:^(NSTimer *t) {
        g_peek = NO;
        nspios_update();
    }];
}
@end

static void nspios_update(void) {
    BOOL want = !g_peek && nspios_tv_connected() && nspios_controller_connected() && nspios_enabled();
    if (want && !g_black) {
        UIWindowScene *scene = nspios_phone_scene();
        if (!scene) return;
        g_black = [[UIWindow alloc] initWithWindowScene:scene];
        g_black.windowLevel = UIWindowLevelAlert + 100;
        g_black.backgroundColor = UIColor.blackColor;
        g_black.rootViewController = [NSPiOSBlackController new];
        g_black.hidden = NO;   // nicht key: die Spiel-Fenster behalten Tastatur und Eingaben
        NSLog(@"NSPiOS: TV and controller connected, the phone screen goes black");
    } else if (!want && g_black) {
        g_black.hidden = YES;
        g_black = nil;
        NSLog(@"NSPiOS: phone screen shown again");
    }
}

__attribute__((constructor)) static void nspios_tv_start(void) {
    dispatch_async(dispatch_get_main_queue(), ^{
        NSNotificationCenter *nc = NSNotificationCenter.defaultCenter;
        NSArray *names = @[UISceneWillConnectNotification, UISceneDidDisconnectNotification, UISceneDidActivateNotification,
                           GCControllerDidConnectNotification, GCControllerDidDisconnectNotification,
                           UIApplicationDidBecomeActiveNotification];
        for (NSString *name in names) {
            [nc addObserverForName:name object:nil queue:NSOperationQueue.mainQueue usingBlock:^(NSNotification *n) {
                // die Shell hängt das Bild erst nach dem Verbinden um: kurz danach noch einmal prüfen
                nspios_update();
                dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(0.5 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{ nspios_update(); });
            }];
        }
        [NSTimer scheduledTimerWithTimeInterval:2 repeats:YES block:^(NSTimer *t) { nspios_update(); }];
        nspios_update();
    });
}
