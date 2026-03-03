import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

// Component for rendering AI response content with basic markdown support
// and collapsible think/reasoning sections
Rectangle {
    id: root
    color: Style.cardBackground
    border.color: Style.cardBorder
    border.width: 1
    radius: 8

    property string text: ""
    property bool showCopyButton: true

    // Parse text into blocks: normal text, code blocks, and think sections
    function parseBlocks(rawText) {
        var blocks = []
        if (!rawText) return blocks

        var remaining = rawText
        var codeBlockRegex = /```(\w*)\n?([\s\S]*?)```/
        var thinkRegex = /<think>([\s\S]*?)<\/think>/

        while (remaining.length > 0) {
            var codeMatch = remaining.match(codeBlockRegex)
            var thinkMatch = remaining.match(thinkRegex)

            // Find the earliest match
            var codeIndex = codeMatch ? remaining.indexOf(codeMatch[0]) : -1
            var thinkIndex = thinkMatch ? remaining.indexOf(thinkMatch[0]) : -1

            var nextIndex = -1
            var nextType = ""
            var nextMatch = null

            if (codeIndex >= 0 && (thinkIndex < 0 || codeIndex < thinkIndex)) {
                nextIndex = codeIndex
                nextType = "code"
                nextMatch = codeMatch
            } else if (thinkIndex >= 0) {
                nextIndex = thinkIndex
                nextType = "think"
                nextMatch = thinkMatch
            }

            if (nextIndex < 0) {
                // No more special blocks
                if (remaining.trim().length > 0)
                    blocks.push({ type: "text", content: remaining })
                break
            }

            // Text before the match
            if (nextIndex > 0) {
                var before = remaining.substring(0, nextIndex)
                if (before.trim().length > 0)
                    blocks.push({ type: "text", content: before })
            }

            if (nextType === "code") {
                blocks.push({
                    type: "code",
                    language: nextMatch[1] || "",
                    content: nextMatch[2] || ""
                })
                remaining = remaining.substring(nextIndex + nextMatch[0].length)
            } else {
                blocks.push({
                    type: "think",
                    content: nextMatch[1] || ""
                })
                remaining = remaining.substring(nextIndex + nextMatch[0].length)
            }
        }

        return blocks
    }

    // Apply inline markdown formatting (bold, inline code)
    function formatInlineMarkdown(str) {
        // Bold: **text** or __text__
        str = str.replace(/\*\*(.*?)\*\*/g, '<b>$1</b>')
        str = str.replace(/__(.*?)__/g, '<b>$1</b>')
        // Inline code: `text`
        str = str.replace(/`([^`]+)`/g, '<code style="background-color:#1a1a22; padding:2px 4px; border-radius:3px; font-family:monospace;">$1</code>')
        // Italic: *text* or _text_ (but not inside already-processed bold)
        str = str.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<i>$1</i>')
        return str
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        spacing: 0

        // Copy button row
        RowLayout {
            Layout.fillWidth: true
            visible: showCopyButton && root.text.length > 0
            spacing: 8

            Item { Layout.fillWidth: true }

            Rectangle {
                Layout.preferredWidth: copyBtnRow.implicitWidth + 16
                Layout.preferredHeight: 28
                radius: 4
                color: copyMa.containsMouse ? Style.surfaceVariant : Style.fieldBackground
                border.color: Style.fieldBorder
                border.width: 1

                Row {
                    id: copyBtnRow
                    anchors.centerIn: parent
                    spacing: 6

                    Text {
                        text: copyConfirm.visible ? "Copied" : "Copy"
                        font.pixelSize: 11
                        color: copyConfirm.visible ? Style.success : Style.textSecondary
                        anchors.verticalCenter: parent.verticalCenter
                    }
                }

                Timer {
                    id: copyConfirm
                    interval: 2000
                    property bool visible: running
                }

                MouseArea {
                    id: copyMa
                    anchors.fill: parent
                    cursorShape: Qt.PointingHandCursor
                    hoverEnabled: true
                    onClicked: {
                        viewModel.copyToClipboard(root.text)
                        copyConfirm.restart()
                    }
                }
            }
        }

        // Scrollable content
        ScrollView {
            Layout.fillWidth: true
            Layout.fillHeight: true
            contentWidth: availableWidth
            clip: true

            ColumnLayout {
                width: parent.width
                spacing: 8

                Repeater {
                    model: parseBlocks(root.text)

                    Loader {
                        Layout.fillWidth: true
                        sourceComponent: {
                            if (modelData.type === "code") return codeBlockComponent
                            if (modelData.type === "think") return thinkBlockComponent
                            return textBlockComponent
                        }

                        property var blockData: modelData
                    }
                }

                // Empty state
                Text {
                    visible: root.text.length === 0
                    text: "No content to display"
                    font.pixelSize: 13
                    color: Style.textHint
                    font.italic: true
                    Layout.alignment: Qt.AlignHCenter
                    Layout.topMargin: 40
                }
            }
        }
    }

    // Text block component
    Component {
        id: textBlockComponent

        Text {
            text: formatInlineMarkdown(blockData.content || "")
            textFormat: Text.RichText
            font.pixelSize: 14
            color: Style.textPrimary
            wrapMode: Text.Wrap
            lineHeight: 1.5
            width: parent ? parent.width : 100
        }
    }

    // Code block component
    Component {
        id: codeBlockComponent

        Rectangle {
            width: parent ? parent.width : 100
            implicitHeight: codeCol.implicitHeight
            color: "#0D0D12"
            border.color: Style.fieldBorder
            border.width: 1
            radius: 6

            ColumnLayout {
                id: codeCol
                anchors.left: parent.left
                anchors.right: parent.right
                spacing: 0

                // Language header
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: blockData.language ? 28 : 0
                    visible: !!blockData.language
                    color: "#15151E"
                    radius: 6

                    // Square off bottom corners
                    Rectangle {
                        anchors.bottom: parent.bottom
                        width: parent.width
                        height: parent.radius
                        color: parent.color
                    }

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 12

                        Text {
                            text: blockData.language || ""
                            font.pixelSize: 11
                            font.capitalization: Font.AllUppercase
                            color: Style.textTertiary
                        }

                        Item { Layout.fillWidth: true }

                        // Copy code button
                        Text {
                            text: "Copy"
                            font.pixelSize: 11
                            color: codeCopyMa.containsMouse ? Style.primary : Style.textTertiary

                            MouseArea {
                                id: codeCopyMa
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                hoverEnabled: true
                                onClicked: viewModel.copyToClipboard(blockData.content || "")
                            }
                        }
                    }
                }

                // Code content
                Text {
                    Layout.fillWidth: true
                    Layout.margins: 12
                    text: blockData.content || ""
                    font.pixelSize: 13
                    font.family: "monospace"
                    color: Style.textPrimary
                    wrapMode: Text.Wrap
                    lineHeight: 1.4
                }
            }
        }
    }

    // Think/reasoning block component
    Component {
        id: thinkBlockComponent

        Rectangle {
            id: thinkBlock
            width: parent ? parent.width : 100
            implicitHeight: thinkCol.implicitHeight
            color: Qt.rgba(Style.surfaceVariant.r, Style.surfaceVariant.g, Style.surfaceVariant.b, 0.3)
            border.color: Qt.rgba(Style.primary.r, Style.primary.g, Style.primary.b, 0.3)
            border.width: 1
            radius: 6

            property bool expanded: false

            ColumnLayout {
                id: thinkCol
                anchors.left: parent.left
                anchors.right: parent.right
                spacing: 0

                // Collapsible header
                Rectangle {
                    Layout.fillWidth: true
                    Layout.preferredHeight: 36
                    color: "transparent"
                    radius: 6

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 12
                        anchors.rightMargin: 12
                        spacing: 8

                        Text {
                            text: thinkBlock.expanded ? "\u25BC" : "\u25B6"
                            font.pixelSize: 10
                            color: Style.textTertiary
                        }

                        Text {
                            text: "Reasoning"
                            font.pixelSize: 12
                            font.bold: true
                            font.italic: true
                            color: Style.info
                        }

                        Item { Layout.fillWidth: true }

                        Text {
                            visible: !thinkBlock.expanded
                            text: {
                                var preview = (blockData.content || "").substring(0, 80).replace(/\n/g, " ")
                                return preview + (preview.length < (blockData.content || "").length ? "..." : "")
                            }
                            font.pixelSize: 11
                            color: Style.textHint
                            elide: Text.ElideRight
                            Layout.maximumWidth: 300
                        }
                    }

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: thinkBlock.expanded = !thinkBlock.expanded
                    }
                }

                // Expanded content
                Text {
                    visible: thinkBlock.expanded
                    Layout.fillWidth: true
                    Layout.leftMargin: 12
                    Layout.rightMargin: 12
                    Layout.bottomMargin: 12
                    text: formatInlineMarkdown(blockData.content || "")
                    textFormat: Text.RichText
                    font.pixelSize: 13
                    color: Style.textSecondary
                    wrapMode: Text.Wrap
                    lineHeight: 1.4
                    font.italic: true
                }
            }
        }
    }
}
