import SwiftUI

// MARK: - Content View (Root TabView)

struct ContentView: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        TabView {
            NavigationStack {
                HubScreen()
            }
            .tabItem { Label("Hub", systemImage: "house.fill") }

            NavigationStack {
                ReportsHubScreen()
            }
            .tabItem { Label("Reports", systemImage: "doc.text.fill") }

            NavigationStack {
                ChatsHubScreen()
            }
            .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right.fill") }

            NavigationStack {
                ModelSearchScreen()
            }
            .tabItem { Label("Models", systemImage: "cpu") }

            NavigationStack {
                SettingsScreen()
            }
            .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .tint(AppColors.primary)
    }
}
