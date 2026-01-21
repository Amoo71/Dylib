import SwiftUI

struct ContentView: View {
    var body: some View {
        VStack(spacing: 20) {
            Image(systemName: "safari.fill")
                .font(.system(size: 60))
                .foregroundColor(.blue)
            
            Text("Cookie Injector")
                .font(.largeTitle)
                .fontWeight(.bold)
            
            Text("Safari Extension for iOS")
                .font(.subheadline)
                .foregroundColor(.secondary)
            
            Divider()
                .padding(.vertical)
            
            VStack(alignment: .leading, spacing: 15) {
                FeatureRow(icon: "checkmark.circle.fill", text: "macOS User Agent Emulation")
                FeatureRow(icon: "checkmark.circle.fill", text: "Zoom Controls")
                FeatureRow(icon: "checkmark.circle.fill", text: "Cookie Injection for Netflix")
            }
            .padding()
            
            Spacer()
            
            VStack(spacing: 10) {
                Text("To enable the extension:")
                    .font(.headline)
                
                Text("1. Open Settings")
                Text("2. Go to Safari > Extensions")
                Text("3. Enable Cookie Injector")
            }
            .font(.subheadline)
            .foregroundColor(.secondary)
            .multilineTextAlignment(.center)
            .padding()
        }
        .padding()
    }
}

struct FeatureRow: View {
    let icon: String
    let text: String
    
    var body: some View {
        HStack {
            Image(systemName: icon)
                .foregroundColor(.green)
            Text(text)
                .font(.body)
        }
    }
}

#Preview {
    ContentView()
}
