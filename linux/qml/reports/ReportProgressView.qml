import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

// Full-screen overlay showing report generation progress
Rectangle {
    id: root
    color: Qt.rgba(0, 0, 0, 0.85)

    property bool isComplete: viewModel.reportProgress >= viewModel.reportTotal && viewModel.reportTotal > 0

    // Block clicks from passing through to content behind
    MouseArea {
        anchors.fill: parent
        onClicked: {} // absorb
    }

    // Center dialog card
    Rectangle {
        id: dialogCard
        anchors.centerIn: parent
        width: Math.min(parent.width - 80, 600)
        height: contentCol.implicitHeight + 64
        color: Style.cardBackground
        border.color: Style.cardBorder
        border.width: 1
        radius: 12

        ColumnLayout {
            id: contentCol
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 24
            spacing: 16

            // Header
            RowLayout {
                Layout.fillWidth: true
                spacing: 12

                Text {
                    text: isComplete ? "Report Complete" : "Generating Reports"
                    font.pixelSize: 20
                    font.bold: true
                    color: isComplete ? Style.success : Style.primary
                }

                Item { Layout.fillWidth: true }

                Text {
                    text: isComplete ? "Done" : "In Progress"
                    font.pixelSize: 12
                    color: isComplete ? Style.success : Style.warning
                }
            }

            // Progress text
            Text {
                text: {
                    if (viewModel.reportTotal === 0) return "Preparing..."
                    if (isComplete) return "All " + viewModel.reportTotal + " reports completed"
                    return "Generating " + viewModel.reportProgress + " of " + viewModel.reportTotal + " reports"
                }
                font.pixelSize: 14
                color: Style.textSecondary
            }

            // Progress bar
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 8
                radius: 4
                color: Style.fieldBackground

                Rectangle {
                    width: viewModel.reportTotal > 0
                           ? parent.width * (viewModel.reportProgress / viewModel.reportTotal)
                           : 0
                    height: parent.height
                    radius: 4
                    color: isComplete ? Style.success : Style.primary

                    Behavior on width {
                        NumberAnimation { duration: 300; easing.type: Easing.OutCubic }
                    }
                }
            }

            // Percentage
            Text {
                text: {
                    if (viewModel.reportTotal === 0) return "0%"
                    return Math.round(viewModel.reportProgress / viewModel.reportTotal * 100) + "%"
                }
                font.pixelSize: 12
                color: Style.textTertiary
                Layout.alignment: Qt.AlignRight
            }

            // Separator
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: 1
                color: Style.cardBorder
            }

            // Agent list header
            Text {
                text: "Report Agents"
                font.pixelSize: 13
                font.bold: true
                color: Style.textSecondary
            }

            // Agent status list
            ListView {
                id: agentList
                Layout.fillWidth: true
                Layout.preferredHeight: Math.min(contentHeight, 300)
                clip: true
                model: viewModel.getReportAgents()
                boundsBehavior: Flickable.StopAtBounds
                spacing: 4

                delegate: Rectangle {
                    width: agentList.width
                    height: 44
                    radius: 6
                    color: Style.fieldBackground
                    border.color: {
                        var status = modelData.status || "PENDING"
                        if (status === "SUCCESS") return Style.success
                        if (status === "ERROR") return Style.error
                        if (status === "RUNNING") return Style.primary
                        return Style.cardBorder
                    }
                    border.width: 1

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 12
                        spacing: 10

                        // Status icon
                        Text {
                            text: {
                                var status = modelData.status || "PENDING"
                                if (status === "PENDING") return "\u23F3"  // hourglass
                                if (status === "RUNNING") return "\uD83D\uDD04"  // arrows
                                if (status === "SUCCESS") return "\u2705"  // check
                                if (status === "ERROR") return "\u274C"    // cross
                                if (status === "STOPPED") return "\u23F9"  // stop
                                return "\u23F3"
                            }
                            font.pixelSize: 16
                        }

                        // Agent info
                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 1

                            Text {
                                text: modelData.agentName || "Unknown"
                                font.pixelSize: 13
                                color: Style.textPrimary
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }

                            Text {
                                text: (modelData.providerId || "") + " / " + (modelData.model || "")
                                font.pixelSize: 11
                                color: Style.textTertiary
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }
                        }

                        // Status label
                        Rectangle {
                            Layout.preferredWidth: statusText.implicitWidth + 16
                            Layout.preferredHeight: 22
                            radius: 11
                            color: {
                                var status = modelData.status || "PENDING"
                                if (status === "SUCCESS") return Qt.rgba(Style.success.r, Style.success.g, Style.success.b, 0.15)
                                if (status === "ERROR") return Qt.rgba(Style.error.r, Style.error.g, Style.error.b, 0.15)
                                if (status === "RUNNING") return Qt.rgba(Style.primary.r, Style.primary.g, Style.primary.b, 0.15)
                                return Qt.rgba(Style.warning.r, Style.warning.g, Style.warning.b, 0.15)
                            }

                            Text {
                                id: statusText
                                anchors.centerIn: parent
                                text: modelData.status || "PENDING"
                                font.pixelSize: 10
                                font.bold: true
                                color: {
                                    var status = modelData.status || "PENDING"
                                    if (status === "SUCCESS") return Style.success
                                    if (status === "ERROR") return Style.error
                                    if (status === "RUNNING") return Style.primary
                                    return Style.warning
                                }
                            }
                        }
                    }

                    // Running animation
                    SequentialAnimation on opacity {
                        running: (modelData.status || "PENDING") === "RUNNING"
                        loops: Animation.Infinite
                        NumberAnimation { to: 0.6; duration: 800 }
                        NumberAnimation { to: 1.0; duration: 800 }
                    }
                }
            }

            // Action buttons
            RowLayout {
                Layout.fillWidth: true
                Layout.topMargin: 8
                spacing: 12

                Item { Layout.fillWidth: true }

                // Stop button (while running)
                Rectangle {
                    visible: !isComplete
                    Layout.preferredWidth: stopBtnText.implicitWidth + 32
                    Layout.preferredHeight: 36
                    radius: 6
                    color: stopBtnMa.containsMouse ? Qt.darker(Style.error, 1.2) : Style.error

                    Text {
                        id: stopBtnText
                        anchors.centerIn: parent
                        text: "Stop"
                        font.pixelSize: 13
                        font.bold: true
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: stopBtnMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: viewModel.stopReports()
                    }
                }

                // Dismiss button (when complete)
                Rectangle {
                    visible: isComplete
                    Layout.preferredWidth: dismissBtnText.implicitWidth + 32
                    Layout.preferredHeight: 36
                    radius: 6
                    color: dismissBtnMa.containsMouse ? Style.secondary : Style.primary

                    Text {
                        id: dismissBtnText
                        anchors.centerIn: parent
                        text: "View Results"
                        font.pixelSize: 13
                        font.bold: true
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: dismissBtnMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: {
                            viewModel.currentSection = "reportHistory"
                        }
                    }
                }
            }
        }
    }
}
