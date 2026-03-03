import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property var entries: []
    property string searchText: ""
    property string expandedId: ""
    property bool showClearDialog: false

    Component.onCompleted: refreshEntries()

    function refreshEntries() {
        entries = viewModel.getPromptHistory()
    }

    function filteredEntries() {
        if (searchText.length === 0) return entries
        var lower = searchText.toLowerCase()
        var result = []
        for (var i = 0; i < entries.length; i++) {
            var e = entries[i]
            if ((e.title || "").toLowerCase().indexOf(lower) >= 0 ||
                (e.prompt || "").toLowerCase().indexOf(lower) >= 0) {
                result.push(e)
            }
        }
        return result
    }

    function formatRelativeTime(timestamp) {
        if (!timestamp) return ""
        var now = Date.now()
        var ts = typeof timestamp === "number" ? timestamp : new Date(timestamp).getTime()
        var diffMs = now - ts
        var diffSec = Math.floor(diffMs / 1000)
        if (diffSec < 60) return "Just now"
        var diffMin = Math.floor(diffSec / 60)
        if (diffMin < 60) return diffMin + (diffMin === 1 ? " minute ago" : " minutes ago")
        var diffHour = Math.floor(diffMin / 60)
        if (diffHour < 24) return diffHour + (diffHour === 1 ? " hour ago" : " hours ago")
        var diffDay = Math.floor(diffHour / 24)
        if (diffDay < 30) return diffDay + (diffDay === 1 ? " day ago" : " days ago")
        var diffMonth = Math.floor(diffDay / 30)
        if (diffMonth < 12) return diffMonth + (diffMonth === 1 ? " month ago" : " months ago")
        var diffYear = Math.floor(diffMonth / 12)
        return diffYear + (diffYear === 1 ? " year ago" : " years ago")
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 20
        spacing: 0

        // Header
        RowLayout {
            Layout.fillWidth: true
            Layout.bottomMargin: 16

            Text {
                text: "Prompt History"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            Item { Layout.fillWidth: true }

            Text {
                text: filteredEntries().length + (filteredEntries().length === 1 ? " entry" : " entries")
                font.pixelSize: 13
                color: Style.textSecondary
            }
        }

        // Search field
        Rectangle {
            Layout.fillWidth: true
            Layout.bottomMargin: 16
            height: 40
            color: Style.fieldBackground
            border.color: promptSearchInput.activeFocus ? Style.primary : Style.fieldBorder
            border.width: 1
            radius: 8

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 12
                anchors.rightMargin: 12
                spacing: 8

                Text {
                    text: "\uD83D\uDD0D"
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                TextInput {
                    id: promptSearchInput
                    Layout.fillWidth: true
                    color: Style.textPrimary
                    font.pixelSize: 14
                    clip: true
                    onTextChanged: root.searchText = text

                    Text {
                        anchors.fill: parent
                        text: "Search prompts..."
                        font.pixelSize: 14
                        color: Style.textHint
                        visible: !promptSearchInput.text && !promptSearchInput.activeFocus
                    }
                }

                Text {
                    text: "\u2715"
                    font.pixelSize: 12
                    color: Style.textSecondary
                    visible: promptSearchInput.text.length > 0

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: { promptSearchInput.text = ""; root.searchText = "" }
                    }
                }
            }
        }

        // Content area
        Item {
            Layout.fillWidth: true
            Layout.fillHeight: true

            // Empty state
            ColumnLayout {
                anchors.centerIn: parent
                spacing: 12
                visible: filteredEntries().length === 0

                Text {
                    text: "\uD83D\uDCDD"
                    font.pixelSize: 48
                    Layout.alignment: Qt.AlignHCenter
                }

                Text {
                    text: entries.length === 0 ? "No prompt history" : "No matching prompts"
                    font.pixelSize: 18
                    font.bold: true
                    color: Style.textPrimary
                    Layout.alignment: Qt.AlignHCenter
                }

                Text {
                    text: entries.length === 0
                          ? "Previously used prompts will appear here"
                          : "Try a different search term"
                    font.pixelSize: 13
                    color: Style.textSecondary
                    Layout.alignment: Qt.AlignHCenter
                }
            }

            // Prompt list
            ListView {
                id: promptListView
                anchors.fill: parent
                visible: filteredEntries().length > 0
                model: filteredEntries()
                clip: true
                spacing: 8
                boundsBehavior: Flickable.StopAtBounds

                ScrollBar.vertical: ScrollBar {
                    policy: ScrollBar.AsNeeded
                }

                delegate: Rectangle {
                    id: promptCard
                    width: promptListView.width
                    implicitHeight: promptCardContent.implicitHeight + 24
                    color: promptCardMouse.containsMouse ? Qt.darker(Style.cardBackground, 0.9) : Style.cardBackground
                    border.color: promptCardMouse.containsMouse ? Style.primary : Style.cardBorder
                    border.width: 1
                    radius: 8

                    property var entry: modelData
                    property bool isExpanded: expandedId === (entry.id || "")

                    MouseArea {
                        id: promptCardMouse
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: {
                            if (isExpanded) {
                                expandedId = ""
                            } else {
                                expandedId = entry.id || ""
                            }
                        }
                    }

                    ColumnLayout {
                        id: promptCardContent
                        anchors.left: parent.left
                        anchors.right: parent.right
                        anchors.top: parent.top
                        anchors.margins: 12
                        spacing: 6

                        // Title row
                        RowLayout {
                            Layout.fillWidth: true

                            Text {
                                text: entry.title || "Untitled"
                                font.pixelSize: 15
                                font.bold: true
                                color: Style.textPrimary
                                elide: Text.ElideRight
                                Layout.fillWidth: true
                            }

                            Text {
                                text: formatRelativeTime(entry.timestamp)
                                font.pixelSize: 11
                                color: Style.textTertiary
                            }
                        }

                        // Preview (collapsed) or full text (expanded)
                        Text {
                            visible: !isExpanded
                            text: {
                                var p = entry.prompt || ""
                                return p.length > 120 ? p.substring(0, 120) + "..." : p
                            }
                            font.pixelSize: 12
                            color: Style.textSecondary
                            wrapMode: Text.WordWrap
                            Layout.fillWidth: true
                            maximumLineCount: 2
                            elide: Text.ElideRight
                        }

                        // Full prompt text (expanded)
                        Rectangle {
                            visible: isExpanded
                            Layout.fillWidth: true
                            implicitHeight: fullPromptText.implicitHeight + 20
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 6

                            Text {
                                id: fullPromptText
                                anchors.left: parent.left
                                anchors.right: parent.right
                                anchors.top: parent.top
                                anchors.margins: 10
                                text: entry.prompt || ""
                                font.pixelSize: 13
                                color: Style.textPrimary
                                wrapMode: Text.WordWrap
                            }
                        }

                        // Action buttons (expanded)
                        RowLayout {
                            visible: isExpanded
                            Layout.fillWidth: true
                            Layout.topMargin: 4
                            spacing: 8

                            // Use button
                            Rectangle {
                                width: useLabel.implicitWidth + 20
                                height: 30
                                radius: 6
                                color: useArea.containsMouse ? Style.surfaceVariant : Style.cardBackground
                                border.color: Style.primary
                                border.width: 1

                                Text {
                                    id: useLabel
                                    anchors.centerIn: parent
                                    text: "Use"
                                    font.pixelSize: 12
                                    color: Style.primary
                                }

                                MouseArea {
                                    id: useArea
                                    anchors.fill: parent
                                    cursorShape: Qt.PointingHandCursor
                                    hoverEnabled: true
                                    onClicked: {
                                        viewModel.addPromptHistoryEntry(entry.title || "", entry.prompt || "")
                                        currentSection = "newReport"
                                        viewModel.currentSection = "newReport"
                                    }
                                }
                            }

                            // Copy button
                            Rectangle {
                                width: copyLabel.implicitWidth + 20
                                height: 30
                                radius: 6
                                color: copyArea.containsMouse ? Style.surfaceVariant : Style.cardBackground
                                border.color: Style.cardBorder
                                border.width: 1

                                Text {
                                    id: copyLabel
                                    anchors.centerIn: parent
                                    text: copyFeedback.visible ? "Copied!" : "Copy"
                                    font.pixelSize: 12
                                    color: copyFeedback.visible ? Style.success : Style.textPrimary
                                }

                                Timer {
                                    id: copyFeedback
                                    interval: 1500
                                    property bool visible: running
                                }

                                MouseArea {
                                    id: copyArea
                                    anchors.fill: parent
                                    cursorShape: Qt.PointingHandCursor
                                    hoverEnabled: true
                                    onClicked: {
                                        viewModel.copyToClipboard(entry.prompt || "")
                                        copyFeedback.restart()
                                    }
                                }
                            }

                            Item { Layout.fillWidth: true }
                        }
                    }
                }
            }
        }

        // Footer with Clear History button
        RowLayout {
            Layout.fillWidth: true
            Layout.topMargin: 12

            Item { Layout.fillWidth: true }

            Rectangle {
                width: clearLabel.implicitWidth + 24
                height: 32
                radius: 6
                color: clearArea.containsMouse ? "#33FF4757" : "transparent"
                border.color: Style.error
                border.width: 1
                visible: entries.length > 0

                Text {
                    id: clearLabel
                    anchors.centerIn: parent
                    text: "Clear History"
                    font.pixelSize: 13
                    color: Style.error
                }

                MouseArea {
                    id: clearArea
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    hoverEnabled: true
                    onClicked: showClearDialog = true
                }
            }
        }
    }

    // Clear history confirmation dialog
    Dialog {
        id: clearDialog
        title: "Clear Prompt History"
        modal: true
        visible: showClearDialog
        anchors.centerIn: parent
        width: 360
        standardButtons: Dialog.Ok | Dialog.Cancel

        onAccepted: {
            viewModel.clearPromptHistory()
            refreshEntries()
            expandedId = ""
            showClearDialog = false
        }
        onRejected: showClearDialog = false

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: ColumnLayout {
            spacing: 12

            Text {
                text: "Are you sure you want to clear all " + entries.length + " prompt history entries?"
                font.pixelSize: 14
                color: Style.textPrimary
                wrapMode: Text.WordWrap
                Layout.fillWidth: true
            }

            Text {
                text: "This action cannot be undone."
                font.pixelSize: 12
                color: Style.error
            }
        }
    }
}
