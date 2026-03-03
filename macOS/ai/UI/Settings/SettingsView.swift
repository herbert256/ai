import SwiftUI

/// General app settings (user name, developer mode, API keys).
struct SettingsView: View {
    @Bindable var viewModel: AppViewModel

    private var generalSettings: GeneralSettings {
        get { viewModel.uiState.generalSettings }
    }

    @State private var userName: String = ""
    @State private var developerMode: Bool = true
    @State private var huggingFaceKey: String = ""
    @State private var openRouterKey: String = ""
    @State private var defaultEmail: String = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                SectionHeader(title: "General", icon: "gear")

                AppTextField(label: "User Name", text: $userName)
                Toggle("Developer Mode", isOn: $developerMode)
                AppTextField(label: "Default Email", text: $defaultEmail)

                Divider()

                SectionHeader(title: "API Keys", icon: "key")

                AppTextField(label: "HuggingFace API Key", text: $huggingFaceKey, isSecure: true)
                AppTextField(label: "OpenRouter API Key", text: $openRouterKey, isSecure: true)

                Divider()

                SectionHeader(title: "Data", icon: "externaldrive")

                HStack(spacing: 12) {
                    Button("Export Settings") {
                        SettingsExporter.exportSettings(viewModel.uiState.aiSettings, viewModel.uiState.generalSettings)
                    }
                    .buttonStyle(.bordered)

                    Button("Import Settings") {
                        SettingsExporter.importSettings { settings, general in
                            if let settings {
                                viewModel.updateSettings(settings)
                            }
                            if let general {
                                viewModel.updateGeneralSettings(general)
                                loadFields()
                            }
                        }
                    }
                    .buttonStyle(.bordered)
                }
            }
            .padding()
        }
        .navigationTitle("Settings")
        .onAppear { loadFields() }
        .onDisappear { saveFields() }
        .onChange(of: userName) { _, _ in saveFields() }
        .onChange(of: developerMode) { _, _ in saveFields() }
        .onChange(of: huggingFaceKey) { _, _ in saveFields() }
        .onChange(of: openRouterKey) { _, _ in saveFields() }
        .onChange(of: defaultEmail) { _, _ in saveFields() }
    }

    private func loadFields() {
        userName = generalSettings.userName
        developerMode = generalSettings.developerMode
        huggingFaceKey = generalSettings.huggingFaceApiKey
        openRouterKey = generalSettings.openRouterApiKey
        defaultEmail = generalSettings.defaultEmail
    }

    private func saveFields() {
        let updated = GeneralSettings(
            userName: userName,
            developerMode: developerMode,
            huggingFaceApiKey: huggingFaceKey,
            openRouterApiKey: openRouterKey,
            defaultEmail: defaultEmail
        )
        if updated != viewModel.uiState.generalSettings {
            viewModel.updateGeneralSettings(updated)
        }
    }
}
