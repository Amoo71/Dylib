import SwiftUI
import WebKit

struct ContentView: View {
    @State private var urlString = "https://google.com"
    @State private var showDevTools = false
    
    var body: some View {
        ZStack {
            // Hintergrund: Tiefes Schwarz für OLED (iPhone 16 Pro)
            Color.black.ignoresSafeArea()
            
            VStack(spacing: 12) {
                // Floating Glass Toolbar
                HStack(spacing: 15) {
                    TextField("Suche oder URL", text: $urlString)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .padding(12)
                        .background(.ultraThinMaterial) // VisionOS Glass-Effect
                        .cornerRadius(20)
                        .foregroundColor(.white)
                    
                    Button(action: { showDevTools.toggle() }) {
                        Image(systemName: showDevTools ? "wrench.and.screwdriver.fill" : "wrench.and.screwdriver")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(.white)
                            .frame(width: 44, height: 44)
                            .background(showDevTools ? Color.blue : Color.white.opacity(0.1))
                            .clipShape(Circle())
                    }
                }
                .padding(.horizontal)
                .padding(.top, 10)

                // Der Browser-Bereich
                BrowserView(url: urlString, showDevTools: $showDevTools)
                    .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
                    .overlay(
                        RoundedRectangle(cornerRadius: 30, style: .continuous)
                            .stroke(Color.white.opacity(0.1), lineWidth: 1)
                    )
                    .padding([.horizontal, .bottom], 8)
                    .shadow(color: .blue.opacity(0.15), radius: 30, x: 0, y: 10)
            }
        }
    }
}

struct BrowserView: UIViewRepresentable {
    let url: String
    @Binding var showDevTools: Bool

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .black
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if let targetUrl = URL(string: url.contains("://") ? url : "https://\(url)") {
            if uiView.url?.host != targetUrl.host {
                uiView.load(URLRequest(url: targetUrl))
            }
        }
        
        if showDevTools {
            let erudaScript = """
            (function () {
                if (!window.eruda) {
                    var script = document.createElement('script');
                    script.src = "https://cdn.jsdelivr.net/npm/eruda";
                    document.body.appendChild(script);
                    script.onload = function () { 
                        eruda.init({
                            tool: ['console', 'elements', 'network', 'resource'],
                            theme: 'dark'
                        });
                        eruda.show();
                        eruda.get('network').enable();
                    };
                } else {
                    eruda.show();
                }
            })();
            """
            uiView.evaluateJavaScript(erudaScript)
        } else {
            uiView.evaluateJavaScript("if(window.eruda) eruda.hide();")
        }
    }
}
