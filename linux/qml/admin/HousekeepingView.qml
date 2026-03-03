import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property var storageInfo: ({})
    property string confirmAction: ""  // which action needs confirmation
    property bool showConfirmDialog: false

    Component.onCompleted: refreshStorage()

    function refreshStorage() {
        storageInfo = viewModel.getStorageInfo() || {}
    }

    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return "0 B"
        var units = ["B", "KB", "MB", "GB"]
        var i = 0
        var b = bytes
        while (b >= 1024 && i < units.length - 1) {
            b /= 1024
            i++
        }
        return b.toFixed(i > 0 ? 1 : 0) + " " + units[i]
    }

    function executeAction() {
        switch (confirmAction) {
        case "clearReports":
            viewModel.clearReports()
            break
        case "clearChatHistory":
            viewModel.clearChatHistory()
            break
        case "clearTraces":
            viewModel.clearTraces()
            break
        case "clearAll":
            viewModel.clearAllStorage()
            break
        case "resetRegistry":
            viewModel.resetProviderRegistry()
            break
        }
        showConfirmDialog = false
        confirmAction = ""
        refreshStorage()
    }

    function confirmTitle() {
        switch (confirmAction) {
        case "clearReports": return "Clear Reports"
        case "clearChatHistory": return "Clear Chat History"
        case "clearTraces": return "Clear API Traces"
        case "clearAll": return "Clear All Storage"
        case "resetRegistry": return "Reset Provider Registry"
        default: return "Confirm"
        }
    }

    function confirmMessage() {
        switch (confirmAction) {
        case "clearReports":
            return "Are you sure you want to delete all " + (storageInfo.reportCount || 0) + " reports? This cannot be undone."
        case "clearChatHistory":
            return "Are you sure you want to delete all " + (storageInfo.chatSessionCount || 0) + " chat sessions? This cannot be undone."
        case "clearTraces":
            return "Are you sure you want to delete all " + (storageInfo.traceCount || 0) + " API traces? This cannot be undone."
        case "clearAll":
            return "Are you sure you want to delete ALL stored data including reports, chat history, and traces? This cannot be undone."
        case "resetRegistry":
            return "Are you sure you want to reset the provider registry to defaults? Custom provider configurations will be lost."
        default:
            return ""
        }
    }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 16

            // Title
            Text {
                text: "Housekeeping"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            Text {
                text: "Manage storage and perform maintenance tasks"
                font.pixelSize: 13
                color: Style.textSecondary
            }

            // Storage info section
            Text {
                text: "Storage"
                font.pixelSize: 16
                font.bold: true
                color: Style.textPrimary
                Layout.topMargin: 8
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: storageColumn.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: storageColumn
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    StorageRow {
                        label: "Reports"
                        size: formatBytes(storageInfo.reportsSize || 0)
                        count: (storageInfo.reportCount || 0) + " files"
                        barFraction: storageInfo.totalSize > 0 ? (storageInfo.reportsSize || 0) / storageInfo.totalSize : 0
                        barColor: Style.primary
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    StorageRow {
                        label: "Chat History"
                        size: formatBytes(storageInfo.chatHistorySize || 0)
                        count: (storageInfo.chatSessionCount || 0) + " sessions"
                        barFraction: storageInfo.totalSize > 0 ? (storageInfo.chatHistorySize || 0) / storageInfo.totalSize : 0
                        barColor: Style.success
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    StorageRow {
                        label: "API Traces"
                        size: formatBytes(storageInfo.traceSize || 0)
                        count: (storageInfo.traceCount || 0) + " traces"
                        barFraction: storageInfo.totalSize > 0 ? (storageInfo.traceSize || 0) / storageInfo.totalSize : 0
                        barColor: Style.warning
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    // Total
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Text {
                            text: "Total"
                            font.pixelSize: 14
                            font.bold: true
                            color: Style.textPrimary
                            Layout.preferredWidth: 140
                        }

                        Item { Layout.fillWidth: true }

                        Text {
                            text: formatBytes(storageInfo.totalSize || 0)
                            font.pixelSize: 14
                            font.bold: true
                            color: Style.primary
                        }
                    }
                }
            }

            // Actions section
            Text {
                text: "Actions"
                font.pixelSize: 16
                font.bold: true
                color: Style.textPrimary
                Layout.topMargin: 8
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: actionsColumn.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: actionsColumn
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    ActionRow {
                        label: "Clear Reports"
                        detail: (storageInfo.reportCount || 0) + " reports, " + formatBytes(storageInfo.reportsSize || 0)
                        btnColor: Style.warning
                        onActionTriggered: {
                            root.confirmAction = "clearReports"
                            root.showConfirmDialog = true
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    ActionRow {
                        label: "Clear Chat History"
                        detail: (storageInfo.chatSessionCount || 0) + " sessions, " + formatBytes(storageInfo.chatHistorySize || 0)
                        btnColor: Style.warning
                        onActionTriggered: {
                            root.confirmAction = "clearChatHistory"
                            root.showConfirmDialog = true
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    ActionRow {
                        label: "Clear API Traces"
                        detail: (storageInfo.traceCount || 0) + " traces, " + formatBytes(storageInfo.traceSize || 0)
                        btnColor: Style.warning
                        onActionTriggered: {
                            root.confirmAction = "clearTraces"
                            root.showConfirmDialog = true
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    ActionRow {
                        label: "Clear All Storage"
                        detail: "Remove all reports, chat history, and traces"
                        btnColor: Style.error
                        onActionTriggered: {
                            root.confirmAction = "clearAll"
                            root.showConfirmDialog = true
                        }
                    }
                }
            }

            // Reset section
            Text {
                text: "Reset"
                font.pixelSize: 16
                font.bold: true
                color: Style.textPrimary
                Layout.topMargin: 8
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: resetColumn.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: resetColumn
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    ActionRow {
                        label: "Reset Provider Registry"
                        detail: "Restore provider definitions to factory defaults"
                        btnColor: Style.error
                        onActionTriggered: {
                            root.confirmAction = "resetRegistry"
                            root.showConfirmDialog = true
                        }
                    }
                }
            }

            Item { Layout.fillHeight: true }
        }
    }

    // Confirmation dialog overlay
    Rectangle {
        anchors.fill: parent
        color: "#88000000"
        visible: root.showConfirmDialog

        MouseArea {
            anchors.fill: parent
            onClicked: root.showConfirmDialog = false
        }

        Rectangle {
            anchors.centerIn: parent
            width: 420
            height: dlgCol.implicitHeight + 48
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 12

            MouseArea { anchors.fill: parent }

            ColumnLayout {
                id: dlgCol
                anchors.fill: parent
                anchors.margins: 24
                spacing: 16

                Text {
                    text: root.confirmTitle()
                    font.pixelSize: 18
                    font.bold: true
                    color: Style.textPrimary
                }

                Text {
                    text: root.confirmMessage()
                    font.pixelSize: 13
                    color: Style.textSecondary
                    wrapMode: Text.Wrap
                    Layout.fillWidth: true
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    Item { Layout.fillWidth: true }

                    Rectangle {
                        Layout.preferredWidth: dlgCancelText.implicitWidth + 24
                        Layout.preferredHeight: 36
                        radius: 6
                        color: dlgCancelMa.containsMouse ? Style.fieldBackground : "transparent"
                        border.color: Style.fieldBorder
                        border.width: 1

                        Text {
                            id: dlgCancelText
                            anchors.centerIn: parent
                            text: "Cancel"
                            font.pixelSize: 12
                            color: Style.textPrimary
                        }

                        MouseArea {
                            id: dlgCancelMa
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            hoverEnabled: true
                            onClicked: root.showConfirmDialog = false
                        }
                    }

                    Rectangle {
                        Layout.preferredWidth: dlgConfirmText.implicitWidth + 24
                        Layout.preferredHeight: 36
                        radius: 6
                        color: dlgConfirmMa.containsMouse ? Qt.darker(Style.error, 1.2) : Style.error

                        Text {
                            id: dlgConfirmText
                            anchors.centerIn: parent
                            text: "Confirm"
                            font.pixelSize: 12
                            font.bold: true
                            color: "#FFFFFF"
                        }

                        MouseArea {
                            id: dlgConfirmMa
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            hoverEnabled: true
                            onClicked: root.executeAction()
                        }
                    }
                }
            }
        }
    }

    // Inline components
    component StorageRow: RowLayout {
        property string label
        property string size
        property string count
        property real barFraction: 0
        property color barColor: Style.primary

        Layout.fillWidth: true
        spacing: 12

        Text {
            text: label
            font.pixelSize: 13
            color: Style.textPrimary
            Layout.preferredWidth: 140
        }

        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: 8
            radius: 4
            color: Style.fieldBackground

            Rectangle {
                width: parent.width * Math.min(barFraction, 1.0)
                height: parent.height
                radius: 4
                color: barColor

                Behavior on width {
                    NumberAnimation { duration: 300; easing.type: Easing.OutCubic }
                }
            }
        }

        Text {
            text: count
            font.pixelSize: 11
            color: Style.textTertiary
            Layout.preferredWidth: 80
            horizontalAlignment: Text.AlignRight
        }

        Text {
            text: size
            font.pixelSize: 13
            font.bold: true
            color: Style.textPrimary
            Layout.preferredWidth: 80
            horizontalAlignment: Text.AlignRight
        }
    }

    component ActionRow: RowLayout {
        property string label
        property string detail
        property color btnColor: Style.warning

        signal actionTriggered()

        Layout.fillWidth: true
        spacing: 12

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 2

            Text {
                text: label
                font.pixelSize: 13
                font.bold: true
                color: Style.textPrimary
            }

            Text {
                text: detail
                font.pixelSize: 11
                color: Style.textTertiary
            }
        }

        Rectangle {
            Layout.preferredWidth: actionBtnLabel.implicitWidth + 24
            Layout.preferredHeight: 32
            radius: 6
            color: actionBtnMa.containsMouse ? Qt.darker(btnColor, 1.2) : Style.fieldBackground
            border.color: btnColor
            border.width: 1

            Text {
                id: actionBtnLabel
                anchors.centerIn: parent
                text: label
                font.pixelSize: 11
                color: btnColor
            }

            MouseArea {
                id: actionBtnMa
                anchors.fill: parent
                cursorShape: Qt.PointingHandCursor
                hoverEnabled: true
                onClicked: actionTriggered()
            }
        }
    }
}
