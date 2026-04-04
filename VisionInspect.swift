import SwiftUI
import WebKit

@main
struct VisionInspectApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

struct ContentView: View {
    @State private var urlString = "https://google.com"
    @State private var showDevTools = false
    
    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            
            VStack(spacing: 10) {
                HStack {
                    TextField("URL...", text: $urlString)
                        .textFieldStyle(PlainTextFieldStyle())
                        .padding(10)
                        .background(Color.white.opacity(0.1))
                        .cornerRadius(15)
                        .foregroundColor(.white)
                    
                    Button(action: { showDevTools.toggle() }) {
                        Image(systemName: "terminal.fill")
                            .padding(10)
                            .background(showDevTools ? Color.blue : Color.gray.opacity(0.3))
                            .clipShape(Circle())
                            .foregroundColor(.white)
                    }
                }
                .padding()

                BrowserWrapper(url: urlString, showDevTools: $showDevTools)
                    .cornerRadius(20)
                    .padding(.bottom, 5)
            }
        }
    }
}

struct BrowserWrapper: UIViewRepresentable {
    let url: String
    @Binding var showDevTools: Bool

    func makeUIView(context: Context) -> WKWebView {
        return WKWebView()
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {
        if let target = URL(string: url.contains("://") ? url : "https://\(url)") {
            if uiView.url == nil || uiView.url?.host != target.host {
                uiView.load(URLRequest(url: target))
            }
        }
        
        if showDevTools {
            let script = "if(!window.eruda){var s=document.createElement('script');s.src='https://cdn.jsdelivr.net/npm/eruda';document.body.appendChild(s);s.onload=function(){eruda.init();eruda.show();eruda.get('network').enable();}}else{eruda.show();}"
            uiView.evaluateJavaScript(script)
        }
    }
}
