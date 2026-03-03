import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "theme"

ApplicationWindow {
    id: root
    visible: true
    width: 1200
    height: 800
    minimumWidth: 1000
    minimumHeight: 700
    title: "AI"
    color: Style.background

    property string currentSection: "hub"

    ListModel {
        id: sidebarModel
        ListElement { section: "hub"; label: "Hub"; group: "Main"; icon: "🏠" }
        ListElement { section: "newReport"; label: "New Report"; group: "Reports"; icon: "📄" }
        ListElement { section: "reportHistory"; label: "Report History"; group: "Reports"; icon: "📋" }
        ListElement { section: "promptHistory"; label: "Prompt History"; group: "Reports"; icon: "📝" }
        ListElement { section: "chat"; label: "Chat"; group: "Chat"; icon: "💬" }
        ListElement { section: "chatHistory"; label: "Chat History"; group: "Chat"; icon: "🕐" }
        ListElement { section: "dualChat"; label: "Dual Chat"; group: "Chat"; icon: "🔄" }
        ListElement { section: "modelSearch"; label: "Model Search"; group: "Tools"; icon: "🔍" }
        ListElement { section: "statistics"; label: "AI Usage"; group: "Tools"; icon: "📊" }
        ListElement { section: "settings"; label: "Settings"; group: "Admin"; icon: "⚙️" }
        ListElement { section: "setup"; label: "AI Setup"; group: "Admin"; icon: "🔧" }
        ListElement { section: "housekeeping"; label: "Housekeeping"; group: "Admin"; icon: "🧹" }
        ListElement { section: "traces"; label: "API Traces"; group: "Admin"; icon: "📡" }
        ListElement { section: "developer"; label: "Developer"; group: "Admin"; icon: "🛠" }
        ListElement { section: "help"; label: "Help"; group: "Admin"; icon: "❓" }
    }

    SplitView {
        anchors.fill: parent
        orientation: Qt.Horizontal

        // Sidebar
        Rectangle {
            SplitView.preferredWidth: 220
            SplitView.minimumWidth: 180
            SplitView.maximumWidth: 300
            color: Style.background

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 0
                spacing: 0

                // App title
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 48
                    color: Style.cardBackground

                    Text {
                        anchors.centerIn: parent
                        text: "AI"
                        font.pixelSize: 20
                        font.bold: true
                        color: Style.primary
                    }
                }

                // Navigation list
                ListView {
                    id: sidebarList
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    model: sidebarModel
                    clip: true
                    boundsBehavior: Flickable.StopAtBounds

                    section.property: "group"
                    section.delegate: Rectangle {
                        width: sidebarList.width
                        height: 32
                        color: "transparent"

                        Text {
                            anchors.left: parent.left
                            anchors.leftMargin: 16
                            anchors.verticalCenter: parent.verticalCenter
                            text: section
                            font.pixelSize: 11
                            font.bold: true
                            font.capitalization: Font.AllUppercase
                            color: Style.textSecondary
                            font.letterSpacing: 1
                        }
                    }

                    delegate: Rectangle {
                        width: sidebarList.width
                        height: 36
                        color: currentSection === model.section ? Style.surfaceVariant : "transparent"
                        radius: 4

                        anchors.leftMargin: 8
                        anchors.rightMargin: 8

                        Row {
                            anchors.fill: parent
                            anchors.leftMargin: 16
                            spacing: 10

                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: model.icon
                                font.pixelSize: 14
                            }

                            Text {
                                anchors.verticalCenter: parent.verticalCenter
                                text: model.label
                                font.pixelSize: 13
                                color: currentSection === model.section ? Style.primary : Style.textPrimary
                            }
                        }

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: {
                                currentSection = model.section
                                viewModel.currentSection = model.section
                            }
                        }

                        Rectangle {
                            visible: currentSection === model.section
                            width: 3
                            height: parent.height - 8
                            anchors.left: parent.left
                            anchors.verticalCenter: parent.verticalCenter
                            color: Style.primary
                            radius: 2
                        }
                    }
                }
            }
        }

        // Content area
        Rectangle {
            SplitView.fillWidth: true
            color: Style.background

            Loader {
                id: contentLoader
                anchors.fill: parent
                source: {
                    switch (currentSection) {
                    case "hub": return "HubView.qml"
                    case "newReport": return "reports/NewReportView.qml"
                    case "reportHistory": return "history/HistoryView.qml"
                    case "promptHistory": return "history/PromptHistoryView.qml"
                    case "chat": return "chat/ChatView.qml"
                    case "chatHistory": return "history/HistoryView.qml"
                    case "dualChat": return "chat/DualChatView.qml"
                    case "modelSearch": return "models/ModelSearchView.qml"
                    case "statistics": return "admin/StatisticsView.qml"
                    case "settings": return "settings/SettingsView.qml"
                    case "setup": return "settings/SetupView.qml"
                    case "housekeeping": return "admin/HousekeepingView.qml"
                    case "traces": return "admin/TraceView.qml"
                    case "developer": return "admin/DeveloperView.qml"
                    case "help": return "admin/HelpView.qml"
                    default: return "HubView.qml"
                    }
                }
            }
        }
    }
}
