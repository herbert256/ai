import SwiftUI

// MARK: - Report Parameters Screen

struct ReportParametersScreen: View {
    @Environment(AppViewModel.self) private var viewModel
    @State private var temperature: String = ""
    @State private var maxTokens: String = ""
    @State private var topP: String = ""
    @State private var topK: String = ""
    @State private var systemPrompt: String = ""
    @State private var searchEnabled = false

    var body: some View {
        Form {
            Section("Generation Parameters") {
                HStack {
                    Text("Temperature")
                    Spacer()
                    TextField("0.0-2.0", text: $temperature)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                        .keyboardType(.decimalPad)
                }
                HStack {
                    Text("Max Tokens")
                    Spacer()
                    TextField("e.g. 4096", text: $maxTokens)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                        .keyboardType(.numberPad)
                }
                HStack {
                    Text("Top P")
                    Spacer()
                    TextField("0.0-1.0", text: $topP)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                        .keyboardType(.decimalPad)
                }
                HStack {
                    Text("Top K")
                    Spacer()
                    TextField("e.g. 40", text: $topK)
                        .multilineTextAlignment(.trailing)
                        .frame(width: 80)
                        .keyboardType(.numberPad)
                }
            }

            Section("System Prompt") {
                TextEditor(text: $systemPrompt)
                    .frame(minHeight: 80)
            }

            Section("Search") {
                Toggle("Enable Search", isOn: $searchEnabled)
            }
        }
        .scrollContentBackground(.hidden)
        .background(AppColors.background)
        .navigationTitle("Report Parameters")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Apply") {
                    let params = AgentParameters(
                        temperature: Float(temperature),
                        maxTokens: Int(maxTokens),
                        topP: Float(topP),
                        topK: Int(topK),
                        systemPrompt: systemPrompt.isEmpty ? nil : systemPrompt,
                        searchEnabled: searchEnabled
                    )
                    viewModel.setReportAdvancedParameters(params)
                }
            }
        }
    }
}
