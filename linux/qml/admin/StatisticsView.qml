import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property var usageStats: []
    property int totalCalls: 0
    property int totalInputTokens: 0
    property int totalOutputTokens: 0
    property bool showClearConfirm: false

    Component.onCompleted: refreshStats()

    function refreshStats() {
        var stats = viewModel.usageStatsList || []
        // Sort by total tokens descending
        stats.sort(function(a, b) {
            var totalA = (a.inputTokens || 0) + (a.outputTokens || 0)
            var totalB = (b.inputTokens || 0) + (b.outputTokens || 0)
            return totalB - totalA
        })
        usageStats = stats

        var calls = 0, inTok = 0, outTok = 0
        for (var i = 0; i < stats.length; i++) {
            calls += (stats[i].calls || 0)
            inTok += (stats[i].inputTokens || 0)
            outTok += (stats[i].outputTokens || 0)
        }
        totalCalls = calls
        totalInputTokens = inTok
        totalOutputTokens = outTok
    }

    function formatCompact(n) {
        if (n >= 1000000000) return (n / 1000000000).toFixed(1) + "B"
        if (n >= 1000000) return (n / 1000000).toFixed(1) + "M"
        if (n >= 1000) return (n / 1000).toFixed(1) + "K"
        return n.toString()
    }

    function formatNumber(n) {
        return n.toLocaleString()
    }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 16

            // Title
            RowLayout {
                Layout.fillWidth: true
                spacing: 12

                Text {
                    text: "AI Usage"
                    font.pixelSize: 24
                    font.bold: true
                    color: Style.primary
                }

                Item { Layout.fillWidth: true }

                Rectangle {
                    Layout.preferredWidth: refreshBtnText.implicitWidth + 24
                    Layout.preferredHeight: 32
                    radius: 6
                    color: refreshBtnMa.containsMouse ? Style.secondary : Style.primary

                    Text {
                        id: refreshBtnText
                        anchors.centerIn: parent
                        text: "Refresh"
                        font.pixelSize: 12
                        font.bold: true
                        color: "#FFFFFF"
                    }

                    MouseArea {
                        id: refreshBtnMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: root.refreshStats()
                    }
                }
            }

            // Summary cards
            GridLayout {
                Layout.fillWidth: true
                columns: 3
                columnSpacing: 12
                rowSpacing: 12

                SummaryCard {
                    label: "Total API Calls"
                    value: formatCompact(root.totalCalls)
                    icon: "API"
                    accentColor: Style.primary
                }

                SummaryCard {
                    label: "Total Input Tokens"
                    value: formatCompact(root.totalInputTokens)
                    icon: "IN"
                    accentColor: Style.info
                }

                SummaryCard {
                    label: "Total Output Tokens"
                    value: formatCompact(root.totalOutputTokens)
                    icon: "OUT"
                    accentColor: Style.success
                }
            }

            // Usage table
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: Math.max(tableColumn.implicitHeight + 32, 200)
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: tableColumn
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 8

                    // Table header
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 36
                        color: Style.fieldBackground
                        radius: 4

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 12
                            anchors.rightMargin: 12
                            spacing: 8

                            Text {
                                text: "Provider"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                                Layout.preferredWidth: 120
                            }

                            Text {
                                text: "Model"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                                Layout.fillWidth: true
                            }

                            Text {
                                text: "Calls"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                                Layout.preferredWidth: 70
                                horizontalAlignment: Text.AlignRight
                            }

                            Text {
                                text: "Input Tokens"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                                Layout.preferredWidth: 100
                                horizontalAlignment: Text.AlignRight
                            }

                            Text {
                                text: "Output Tokens"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                                Layout.preferredWidth: 100
                                horizontalAlignment: Text.AlignRight
                            }

                            Text {
                                text: "Total Tokens"
                                font.pixelSize: 11
                                font.bold: true
                                color: Style.textSecondary
                                Layout.preferredWidth: 100
                                horizontalAlignment: Text.AlignRight
                            }
                        }
                    }

                    // Table rows
                    Repeater {
                        model: root.usageStats.length

                        Rectangle {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 40
                            color: index % 2 === 0 ? "transparent" : Qt.rgba(Style.fieldBackground.r, Style.fieldBackground.g, Style.fieldBackground.b, 0.4)
                            radius: 4

                            property var stat: root.usageStats[index]

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 12
                                anchors.rightMargin: 12
                                spacing: 8

                                Text {
                                    text: stat.provider || ""
                                    font.pixelSize: 12
                                    color: Style.primary
                                    Layout.preferredWidth: 120
                                    elide: Text.ElideRight
                                }

                                Text {
                                    text: stat.model || ""
                                    font.pixelSize: 12
                                    color: Style.textPrimary
                                    Layout.fillWidth: true
                                    elide: Text.ElideRight
                                }

                                Text {
                                    text: formatNumber(stat.calls || 0)
                                    font.pixelSize: 12
                                    color: Style.textPrimary
                                    Layout.preferredWidth: 70
                                    horizontalAlignment: Text.AlignRight
                                }

                                Text {
                                    text: formatCompact(stat.inputTokens || 0)
                                    font.pixelSize: 12
                                    color: Style.info
                                    Layout.preferredWidth: 100
                                    horizontalAlignment: Text.AlignRight
                                }

                                Text {
                                    text: formatCompact(stat.outputTokens || 0)
                                    font.pixelSize: 12
                                    color: Style.success
                                    Layout.preferredWidth: 100
                                    horizontalAlignment: Text.AlignRight
                                }

                                Text {
                                    text: formatCompact((stat.inputTokens || 0) + (stat.outputTokens || 0))
                                    font.pixelSize: 12
                                    font.bold: true
                                    color: Style.textPrimary
                                    Layout.preferredWidth: 100
                                    horizontalAlignment: Text.AlignRight
                                }
                            }
                        }
                    }

                    // Empty state
                    Text {
                        visible: root.usageStats.length === 0
                        text: "No usage data yet"
                        font.pixelSize: 14
                        color: Style.textHint
                        font.italic: true
                        Layout.alignment: Qt.AlignHCenter
                        Layout.topMargin: 40
                    }
                }
            }

            // Clear button
            Rectangle {
                Layout.preferredWidth: clearBtnText.implicitWidth + 24
                Layout.preferredHeight: 36
                radius: 6
                color: clearBtnMa.containsMouse ? Qt.darker(Style.error, 1.2) : Style.fieldBackground
                border.color: Style.error
                border.width: 1
                visible: root.usageStats.length > 0

                Text {
                    id: clearBtnText
                    anchors.centerIn: parent
                    text: "Clear Statistics"
                    font.pixelSize: 12
                    color: Style.error
                }

                MouseArea {
                    id: clearBtnMa
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    hoverEnabled: true
                    onClicked: root.showClearConfirm = true
                }
            }

            Item { Layout.fillHeight: true }
        }
    }

    // Confirmation dialog overlay
    Rectangle {
        anchors.fill: parent
        color: "#88000000"
        visible: root.showClearConfirm

        MouseArea {
            anchors.fill: parent
            onClicked: root.showClearConfirm = false
        }

        Rectangle {
            anchors.centerIn: parent
            width: 400
            height: confirmCol.implicitHeight + 48
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 12

            MouseArea { anchors.fill: parent }

            ColumnLayout {
                id: confirmCol
                anchors.fill: parent
                anchors.margins: 24
                spacing: 16

                Text {
                    text: "Clear Statistics"
                    font.pixelSize: 18
                    font.bold: true
                    color: Style.textPrimary
                }

                Text {
                    text: "Are you sure you want to clear all usage statistics? This action cannot be undone."
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
                        Layout.preferredWidth: cancelText.implicitWidth + 24
                        Layout.preferredHeight: 36
                        radius: 6
                        color: cancelMa.containsMouse ? Style.fieldBackground : "transparent"
                        border.color: Style.fieldBorder
                        border.width: 1

                        Text {
                            id: cancelText
                            anchors.centerIn: parent
                            text: "Cancel"
                            font.pixelSize: 12
                            color: Style.textPrimary
                        }

                        MouseArea {
                            id: cancelMa
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            hoverEnabled: true
                            onClicked: root.showClearConfirm = false
                        }
                    }

                    Rectangle {
                        Layout.preferredWidth: confirmText.implicitWidth + 24
                        Layout.preferredHeight: 36
                        radius: 6
                        color: confirmMa.containsMouse ? Qt.darker(Style.error, 1.2) : Style.error

                        Text {
                            id: confirmText
                            anchors.centerIn: parent
                            text: "Clear All"
                            font.pixelSize: 12
                            font.bold: true
                            color: "#FFFFFF"
                        }

                        MouseArea {
                            id: confirmMa
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            hoverEnabled: true
                            onClicked: {
                                viewModel.clearStatistics()
                                root.showClearConfirm = false
                                root.refreshStats()
                            }
                        }
                    }
                }
            }
        }
    }

    // Inline components
    component SummaryCard: Rectangle {
        property string label
        property string value
        property string icon
        property color accentColor: Style.primary

        Layout.fillWidth: true
        Layout.preferredHeight: 100
        color: Style.cardBackground
        border.color: Style.cardBorder
        border.width: 1
        radius: 8

        ColumnLayout {
            anchors.centerIn: parent
            spacing: 6

            Rectangle {
                Layout.alignment: Qt.AlignHCenter
                width: 36
                height: 36
                radius: 18
                color: Qt.rgba(accentColor.r, accentColor.g, accentColor.b, 0.15)

                Text {
                    anchors.centerIn: parent
                    text: icon
                    font.pixelSize: 12
                    font.bold: true
                    color: accentColor
                }
            }

            Text {
                text: value
                font.pixelSize: 26
                font.bold: true
                color: accentColor
                Layout.alignment: Qt.AlignHCenter
            }

            Text {
                text: label
                font.pixelSize: 11
                color: Style.textSecondary
                Layout.alignment: Qt.AlignHCenter
            }
        }
    }
}
