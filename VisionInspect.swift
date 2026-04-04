import SwiftUI
import WebKit

@main
struct VisionInspectApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}

struct ContentView: View {
    @State private var urlString = "https://google.com"
    @State private var showDevTools = false
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            VStack(spacing: 12) {
                // Glassmorphism Adressleiste
                HStack {
                    TextField("URL eingeben", text: $urlString)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .padding(12)
                        .background(.ultraThinMaterial)
                        .cornerRadius(20)
                        .foregroundColor(.white)
                        .onSubmit { showDevTools = false }
                    
                    Button(action: { showDevTools.toggle() }) {
                        Image(systemName: "terminal.fill")
                            .foregroundColor(.white)
                            .padding(12)
                            .background(showDevTools ? Color.blue : Color.white.opacity(0.2))
                            .clipShape(Circle())
                    }
                }
                .padding(.horizontal)
                .padding(.top, 10)

                // Browser Engine
                BrowserView(url: urlString, showDevTools: $showDevTools)
                    .clipShape(RoundedRectangle(cornerRadius: 30, style: .continuous))
                    .padding([.horizontal, .bottom], 8)
            }
        }
    }
}

struct BrowserView: UIViewRepresentable {
    let url: String
    @Binding var showDevTools: Bool

    func makeUIView(context: Context) -> WKWebView {
        let webView = WKWebView()
        webView.backgroundColor = .black
        webView.isOpaque = false
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if let targetUrl = URL(string: url.contains("://") ? url : "https://\(url)") {
            if uiView.url?.host != targetUrl.host {
                uiView.load(URLRequest(url: targetUrl))
            }
        }
        
        if showDevTools {
            let script = """
            (function () {
                if (!window.eruda) {
                    var s = document.createElement('script');
                    s.src = "https://cdn.jsdelivr.net/npm/eruda";
                    document.body.appendChild(s);
                    s.onload = function () { 
                        eruda.init({theme: 'dark'}); 
                        eruda.show(); 
                        eruda.get('network').enable();
                    };
                } else { eruda.show(); }
            })();
            """
            uiView.evaluateJavaScript(script)
        } else {
            uiView.evaluateJavaScript("if(window.eruda) eruda.hide();")
        }
    }
}
