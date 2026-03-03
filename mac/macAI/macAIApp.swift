import SwiftUI

@main
struct macAIApp: App {
    @State private var viewModel = AppViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView(viewModel: viewModel)
                .task {
                    await viewModel.bootstrap()
                }
        }
        .defaultSize(width: 1200, height: 800)
    }
}
