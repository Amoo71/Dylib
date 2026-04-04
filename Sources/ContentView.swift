import SwiftUI
import WebKit

struct ContentView: View {
    @State private var urlString = "https://google.com"
    @State private var showDevTools = false
    @State private var reloadTrigger = false
    
    var body: some View {
        ZStack {
            // Tiefschwarzer Hintergrund
            Color.black.ignoresSafeArea()
            
            VStack(spacing: 0) {
                // Floating Glass Adressleiste
                HStack {
                    TextField("URL...", text: $urlString)
                        .keyboardType(.URL)
                        .autocapitalization(.none)
                        .padding(12)
                        .background(.ultraThinMaterial)
                        .cornerRadius(20)
                        .foregroundColor(.white)
                        .onSubmit { reloadTrigger.toggle() }
                    
                    Button(action: { showDevTools.toggle() }) {
                        Image(systemName: "terminal.fill")
                            .font(.system(size: 20))
                            .foregroundColor(.white)
                            .padding(12)
                            .background(showDevTools ? Color.blue : Color.gray.opacity(0.3))
                            .clipShape(Circle())
                    }
                }
                .padding()
                .background(.black.opacity(0.5))

                // Web-View
                BrowserView(url: urlString, showDevTools: $showDevTools, reload: reloadTrigger)
                    .cornerRadius(25)
                    .padding(.horizontal, 8)
                    .padding(.bottom, 8)
                    .shadow(color: .blue.opacity(0.2), radius: 20)
            }
        }
    }
}

struct BrowserView: UIViewRepresentable {
    let url: String
    @Binding var showDevTools: Bool
    let reload: Bool

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let webView = WKWebView(frame: .zero, configuration: config)
        webView.backgroundColor = .black
        webView.isOpaque = false
        return webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if let targetUrl = URL(string: url) {
            let request = URLRequest(url: targetUrl)
            uiView.load(request)
        }
        
        // Eruda DevTools Injection (Network, Elements, Console, Storage)
        if showDevTools {
            let script = """
            (function () {
                if (!window.eruda) {
                    var script = document.createElement('script');
                    script.src = "https://cdn.jsdelivr.net/npm/eruda";
                    document.body.appendChild(script);
                    script.onload = function () { 
                        eruda.init(); 
                        eruda.show();
                        eruda.get('network').enable();
                    };
                } else {
                    eruda.show();
                }
            })();
            """
            uiView.evaluateJavaScript(script)
        } else {
            uiView.evaluateJavaScript("if(window.eruda) eruda.hide();")
        }
    }
}
