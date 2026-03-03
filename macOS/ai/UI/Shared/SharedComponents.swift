import SwiftUI

// MARK: - Delete Confirmation Dialog

/// Reusable delete confirmation dialog.
struct DeleteConfirmationDialog: View {
    let entityType: String
    let entityName: String
    let onConfirm: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text("Delete \(entityType)")
                .font(.headline)
            Text("Are you sure you want to delete \"\(entityName)\"?")
                .font(.body)
                .foregroundStyle(.secondary)
            HStack(spacing: 12) {
                Button("Cancel") { onDismiss() }
                    .keyboardShortcut(.escape, modifiers: [])
                Button("Delete") { onConfirm() }
                    .foregroundStyle(.red)
                    .keyboardShortcut(.return, modifiers: [])
            }
        }
        .padding(20)
        .frame(width: 300)
    }
}

// MARK: - Settings List Item Card

/// Reusable list item card with title, subtitle, and delete button.
struct SettingsListItemCard: View {
    let title: String
    let subtitle: String
    var extraLine: String? = nil
    var subtitleColor: Color = AppColors.textSecondary
    let onClick: () -> Void
    let onDelete: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .font(.system(size: 14, weight: .semibold))
                    .foregroundStyle(.white)
                Text(subtitle)
                    .font(.system(size: 12))
                    .foregroundStyle(subtitleColor)
                if let extraLine {
                    Text(extraLine)
                        .font(.system(size: 11))
                        .foregroundStyle(AppColors.textTertiary)
                }
            }
            Spacer()
            Button(action: onDelete) {
                Image(systemName: "trash")
                    .foregroundStyle(.red)
            }
            .buttonStyle(.plain)
        }
        .padding(12)
        .background(AppColors.cardBackground)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(
            RoundedRectangle(cornerRadius: 8)
                .stroke(AppColors.cardBorder, lineWidth: 1)
        )
        .contentShape(Rectangle())
        .onTapGesture { onClick() }
    }
}

// MARK: - Outlined Text Field Style

/// Consistent text field style used across settings screens.
struct AppTextField: View {
    let label: String
    @Binding var text: String
    var isSecure: Bool = false
    var axis: Axis = .horizontal

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
            if isSecure {
                SecureField("", text: $text)
                    .textFieldStyle(.roundedBorder)
            } else {
                TextField("", text: $text, axis: axis)
                    .textFieldStyle(.roundedBorder)
            }
        }
    }
}

// MARK: - Section Header

/// Consistent section header used across settings screens.
struct SectionHeader: View {
    let title: String
    var icon: String? = nil

    var body: some View {
        HStack(spacing: 6) {
            if let icon {
                Image(systemName: icon)
                    .foregroundStyle(AppColors.primary)
            }
            Text(title)
                .font(.headline)
                .foregroundStyle(.white)
        }
        .padding(.top, 8)
    }
}

// MARK: - Empty State View

/// Placeholder for empty lists (no agents, no reports, etc.)
struct EmptyStateView: View {
    let icon: String
    let title: String
    let message: String
    var actionLabel: String? = nil
    var action: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: icon)
                .font(.system(size: 36))
                .foregroundStyle(AppColors.primary.opacity(0.5))
            Text(title)
                .font(.title3.bold())
            Text(message)
                .font(.body)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            if let actionLabel, let action {
                Button(actionLabel, action: action)
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding()
    }
}

// MARK: - Provider State Badge

/// Small colored badge showing provider connection status.
struct ProviderStateBadge: View {
    let state: String

    var body: some View {
        Circle()
            .fill(AppColors.colorForState(state))
            .frame(width: 8, height: 8)
    }
}

// MARK: - Loading Indicator

/// Small inline loading indicator.
struct InlineLoadingView: View {
    let message: String

    var body: some View {
        HStack(spacing: 8) {
            ProgressView()
                .controlSize(.small)
            Text(message)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: - Cost Display

/// Formatted cost display with appropriate coloring.
struct CostDisplay: View {
    let cost: Double?
    let source: String?

    var body: some View {
        if let cost {
            let isReal = source != "DEFAULT"
            Text(formatUsd(cost))
                .font(.caption.monospacedDigit())
                .foregroundStyle(isReal ? AppColors.statusError : AppColors.textTertiary)
        }
    }
}

// MARK: - Token Count Display

struct TokenCountDisplay: View {
    let inputTokens: Int
    let outputTokens: Int

    var body: some View {
        HStack(spacing: 4) {
            Text("↑\(formatCompactNumber(Int64(inputTokens)))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.primary)
            Text("↓\(formatCompactNumber(Int64(outputTokens)))")
                .font(.caption.monospacedDigit())
                .foregroundStyle(AppColors.statusOk)
        }
    }
}

// MARK: - Scroll-to-Top Helpers

extension View {
    /// Wrap in a ScrollView with a navigation title.
    func scrollableDetail(_ title: String) -> some View {
        ScrollView {
            self
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding()
        }
        .navigationTitle(title)
    }
}
