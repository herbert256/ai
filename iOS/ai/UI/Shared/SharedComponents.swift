import SwiftUI

// MARK: - Title Bar

struct TitleBar: View {
    let title: String
    var subtitle: String? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.title2)
                .fontWeight(.bold)
                .foregroundStyle(AppColors.onSurface)
            if let subtitle = subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(AppColors.dimText)
            }
        }
    }
}

// MARK: - Card View

struct CardView<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        content
            .padding(12)
            .background(AppColors.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 12))
            .overlay(
                RoundedRectangle(cornerRadius: 12)
                    .stroke(AppColors.cardBorder, lineWidth: 0.5)
            )
    }
}

// MARK: - Settings List Item Card

struct SettingsListItemCard: View {
    let title: String
    var subtitle: String? = nil
    var trailing: String? = nil
    var icon: String? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let icon = icon {
                Image(systemName: icon)
                    .foregroundStyle(AppColors.primary)
                    .frame(width: 24)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .foregroundStyle(AppColors.onSurface)
                if let subtitle = subtitle {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(AppColors.dimText)
                }
            }
            Spacer()
            if let trailing = trailing {
                Text(trailing)
                    .font(.caption)
                    .foregroundStyle(AppColors.dimText)
            }
            Image(systemName: "chevron.right")
                .font(.caption)
                .foregroundStyle(AppColors.dimText)
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 12)
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }
}

// MARK: - Delete Confirmation Dialog

struct DeleteConfirmationDialog: ViewModifier {
    @Binding var isPresented: Bool
    let title: String
    let message: String
    let onConfirm: () -> Void

    func body(content: Content) -> some View {
        content.alert(title, isPresented: $isPresented) {
            Button("Delete", role: .destructive) { onConfirm() }
            Button("Cancel", role: .cancel) { }
        } message: {
            Text(message)
        }
    }
}

extension View {
    func deleteConfirmation(isPresented: Binding<Bool>, title: String, message: String, onConfirm: @escaping () -> Void) -> some View {
        modifier(DeleteConfirmationDialog(isPresented: isPresented, title: title, message: message, onConfirm: onConfirm))
    }
}

// MARK: - Loading Overlay

struct LoadingOverlay: View {
    let message: String

    var body: some View {
        ZStack {
            Color.black.opacity(0.5).ignoresSafeArea()
            VStack(spacing: 16) {
                ProgressView()
                    .tint(AppColors.primary)
                Text(message)
                    .foregroundStyle(AppColors.onSurface)
            }
            .padding(24)
            .background(AppColors.cardBackground)
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }
}

// MARK: - Empty State View

struct EmptyStateView: View {
    let icon: String
    let title: String
    var message: String? = nil

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.largeTitle)
                .foregroundStyle(AppColors.dimText)
            Text(title)
                .font(.headline)
                .foregroundStyle(AppColors.onSurface)
            if let message = message {
                Text(message)
                    .font(.subheadline)
                    .foregroundStyle(AppColors.dimText)
                    .multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

// MARK: - Provider State Indicator

struct ProviderStateIndicator: View {
    let state: String

    var color: Color {
        switch state {
        case "ok": return AppColors.stateOk
        case "error": return AppColors.stateError
        case "inactive": return AppColors.stateInactive
        default: return AppColors.stateNotUsed
        }
    }

    var body: some View {
        Circle()
            .fill(color)
            .frame(width: 8, height: 8)
    }
}

// MARK: - Section Header

struct SectionHeader: View {
    let title: String
    var count: Int? = nil

    var body: some View {
        HStack {
            Text(title)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundStyle(AppColors.primary)
            if let count = count {
                Text("(\(count))")
                    .font(.caption)
                    .foregroundStyle(AppColors.dimText)
            }
            Spacer()
        }
        .padding(.horizontal, 4)
    }
}
