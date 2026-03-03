import SwiftUI

// MARK: - Settings Screen

struct SettingsScreen: View {
    @Environment(AppViewModel.self) private var viewModel

    var body: some View {
        @Bindable var vm = viewModel
        let generalSettings = viewModel.uiState.generalSettings

        ScrollView {
            VStack(spacing: 16) {
                // User section
                CardView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("User")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(AppColors.onSurface)

                        TextField("Your name", text: Binding(
                            get: { generalSettings.userName },
                            set: { viewModel.updateGeneralSettings { $0.userName = $1 }($0) }
                        ))
                        .textFieldStyle(.roundedBorder)

                        TextField("Default email", text: Binding(
                            get: { generalSettings.defaultEmail },
                            set: { viewModel.updateGeneralSettings { $0.defaultEmail = $1 }($0) }
                        ))
                        .textFieldStyle(.roundedBorder)
                        .keyboardType(.emailAddress)
                    }
                }
                .padding(.horizontal)

                // Display section
                CardView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Display")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                            .foregroundStyle(AppColors.onSurface)

                        Toggle("Developer Mode", isOn: Binding(
                            get: { generalSettings.developerMode },
                            set: { viewModel.updateGeneralSettings { $0.developerMode = $1 }($0) }
                        ))
                        .tint(AppColors.primary)
                    }
                }
                .padding(.horizontal)

                // Navigation cards
                NavigationLink(destination: SetupScreen()) {
                    SettingsListItemCard(title: "AI Setup", subtitle: "Providers, agents, flocks", icon: "cpu")
                }
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .background(AppColors.background)
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

extension AppViewModel {
    func updateGeneralSettings(_ update: @escaping (inout GeneralSettings, String) -> Void) -> (String) -> Void {
        return { [self] value in
            var gs = uiState.generalSettings
            update(&gs, value)
            uiState.generalSettings = gs
            SettingsPreferences.shared.saveGeneralSettings(gs)
        }
    }

    func updateGeneralSettings(_ update: @escaping (inout GeneralSettings, Bool) -> Void) -> (Bool) -> Void {
        return { [self] value in
            var gs = uiState.generalSettings
            update(&gs, value)
            uiState.generalSettings = gs
            SettingsPreferences.shared.saveGeneralSettings(gs)
        }
    }
}
