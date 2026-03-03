import SwiftUI

@main
struct AIApp: App {
    @State private var viewModel = AppViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(viewModel)
                .preferredColorScheme(.dark)
                .task {
                    await viewModel.bootstrap()
                }
        }
    }
}
