import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "theme"

Rectangle {
    color: Style.background

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 20

            // Title
            Text {
                text: "Welcome to AI"
                font.pixelSize: 28
                font.bold: true
                color: Style.primary
            }

            Text {
                text: "Multi-platform AI-powered reports and chat using 31 AI services"
                font.pixelSize: 14
                color: Style.textSecondary
            }

            // Stats cards
            GridLayout {
                Layout.fillWidth: true
                columns: 4
                columnSpacing: 12
                rowSpacing: 12

                StatCard { label: "Providers"; value: viewModel.providerCount; icon: "🌐" }
                StatCard { label: "Agents"; value: viewModel.agentCount; icon: "🤖" }
                StatCard { label: "Flocks"; value: viewModel.flockCount; icon: "🐦" }
                StatCard { label: "Swarms"; value: viewModel.swarmCount; icon: "🐝" }
            }

            // Quick actions
            Text {
                text: "Quick Actions"
                font.pixelSize: 18
                font.bold: true
                color: Style.textPrimary
                Layout.topMargin: 12
            }

            GridLayout {
                Layout.fillWidth: true
                columns: 3
                columnSpacing: 12
                rowSpacing: 12

                QuickActionButton {
                    label: "New Report"
                    icon: "📄"
                    accentColor: Style.primary
                    onClicked: {
                        currentSection = "newReport"
                        viewModel.currentSection = "newReport"
                    }
                }

                QuickActionButton {
                    label: "Chat"
                    icon: "💬"
                    accentColor: Style.success
                    onClicked: {
                        currentSection = "chat"
                        viewModel.currentSection = "chat"
                    }
                }

                QuickActionButton {
                    label: "AI Setup"
                    icon: "🔧"
                    accentColor: Style.warning
                    onClicked: {
                        currentSection = "setup"
                        viewModel.currentSection = "setup"
                    }
                }
            }

            // Active providers
            Text {
                text: "Active Providers: " + viewModel.activeProviderCount
                font.pixelSize: 14
                color: Style.textSecondary
                Layout.topMargin: 8
            }

            Item { Layout.fillHeight: true }
        }
    }

    component StatCard: Rectangle {
        property string label
        property int value
        property string icon

        Layout.fillWidth: true
        Layout.preferredHeight: 90
        color: Style.cardBackground
        border.color: Style.cardBorder
        border.width: 1
        radius: 8

        ColumnLayout {
            anchors.centerIn: parent
            spacing: 4

            Text {
                text: icon
                font.pixelSize: 24
                Layout.alignment: Qt.AlignHCenter
            }

            Text {
                text: value.toString()
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
                Layout.alignment: Qt.AlignHCenter
            }

            Text {
                text: label
                font.pixelSize: 12
                color: Style.textSecondary
                Layout.alignment: Qt.AlignHCenter
            }
        }
    }

    component QuickActionButton: Rectangle {
        property string label
        property string icon
        property color accentColor: Style.primary

        signal clicked()

        Layout.fillWidth: true
        Layout.preferredHeight: 70
        color: Style.cardBackground
        border.color: mouseArea.containsMouse ? accentColor : Style.cardBorder
        border.width: 1
        radius: 8

        Row {
            anchors.centerIn: parent
            spacing: 10

            Text {
                text: icon
                font.pixelSize: 20
                anchors.verticalCenter: parent.verticalCenter
            }

            Text {
                text: label
                font.pixelSize: 14
                font.bold: true
                color: accentColor
                anchors.verticalCenter: parent.verticalCenter
            }
        }

        MouseArea {
            id: mouseArea
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            hoverEnabled: true
            onClicked: parent.clicked()
        }
    }
}
