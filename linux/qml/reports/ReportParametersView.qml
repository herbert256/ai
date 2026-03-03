import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

// Advanced report parameter configuration panel
Rectangle {
    id: root
    color: Style.background

    // Current parameter values
    property real temperature: 0.7
    property int maxTokens: 4096
    property real topP: 1.0
    property int topK: 40
    property real frequencyPenalty: 0.0
    property real presencePenalty: 0.0
    property string systemPrompt: ""
    property bool responseFormatJson: false
    property bool searchEnabled: false

    // Output: the assembled parameter map for viewModel
    function getParameters() {
        return {
            "temperature": temperature,
            "maxTokens": maxTokens,
            "topP": topP,
            "topK": topK,
            "frequencyPenalty": frequencyPenalty,
            "presencePenalty": presencePenalty,
            "systemPrompt": systemPrompt,
            "responseFormatJson": responseFormatJson,
            "searchEnabled": searchEnabled
        }
    }

    function resetDefaults() {
        temperature = 0.7
        maxTokens = 4096
        topP = 1.0
        topK = 40
        frequencyPenalty = 0.0
        presencePenalty = 0.0
        systemPrompt = ""
        responseFormatJson = false
        searchEnabled = false
    }

    signal applied()

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 16

            // Header
            Text {
                text: "Report Parameters"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            Text {
                text: "Configure advanced AI generation parameters"
                font.pixelSize: 13
                color: Style.textSecondary
            }

            // Temperature
            ParameterCard {
                Layout.fillWidth: true
                paramLabel: "Temperature"
                paramDescription: "Controls randomness. Lower values make output more focused, higher values more creative."

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    RowLayout {
                        Layout.fillWidth: true
                        Text { text: "0"; font.pixelSize: 11; color: Style.textTertiary }
                        Item { Layout.fillWidth: true }
                        Text { text: temperature.toFixed(1); font.pixelSize: 13; font.bold: true; color: Style.primary }
                        Item { Layout.fillWidth: true }
                        Text { text: "2"; font.pixelSize: 11; color: Style.textTertiary }
                    }

                    Slider {
                        Layout.fillWidth: true
                        from: 0; to: 2; stepSize: 0.1
                        value: temperature
                        onMoved: temperature = value
                        palette.dark: Style.primary
                        palette.midlight: Style.fieldBackground
                    }
                }
            }

            // Max Tokens
            ParameterCard {
                Layout.fillWidth: true
                paramLabel: "Max Tokens"
                paramDescription: "Maximum number of tokens in the response."

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    SpinBox {
                        id: maxTokensSpin
                        from: 1; to: 128000; stepSize: 100
                        value: maxTokens
                        onValueModified: maxTokens = value
                        editable: true
                        Layout.preferredWidth: 180

                        contentItem: TextInput {
                            text: maxTokensSpin.textFromValue(maxTokensSpin.value, maxTokensSpin.locale)
                            font.pixelSize: 13
                            color: Style.textPrimary
                            selectionColor: Style.primary
                            selectedTextColor: Style.textPrimary
                            horizontalAlignment: Qt.AlignHCenter
                            verticalAlignment: Qt.AlignVCenter
                            readOnly: !maxTokensSpin.editable
                            validator: maxTokensSpin.validator
                            inputMethodHints: Qt.ImhDigitsOnly
                        }

                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                        }
                    }

                    // Quick presets
                    Repeater {
                        model: [1024, 4096, 16384, 65536]

                        Rectangle {
                            Layout.preferredWidth: presetText.implicitWidth + 16
                            Layout.preferredHeight: 28
                            radius: 4
                            color: maxTokens === modelData
                                   ? Style.primary
                                   : (presetMa.containsMouse ? Style.surfaceVariant : Style.fieldBackground)
                            border.color: Style.fieldBorder
                            border.width: maxTokens === modelData ? 0 : 1

                            Text {
                                id: presetText
                                anchors.centerIn: parent
                                text: {
                                    if (modelData >= 1024) return (modelData / 1024) + "K"
                                    return modelData.toString()
                                }
                                font.pixelSize: 11
                                color: maxTokens === modelData ? Style.textPrimary : Style.textSecondary
                            }

                            MouseArea {
                                id: presetMa
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                hoverEnabled: true
                                onClicked: {
                                    maxTokens = modelData
                                    maxTokensSpin.value = modelData
                                }
                            }
                        }
                    }
                }
            }

            // Top P
            ParameterCard {
                Layout.fillWidth: true
                paramLabel: "Top P"
                paramDescription: "Nucleus sampling. Consider tokens with cumulative probability above this threshold."

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    RowLayout {
                        Layout.fillWidth: true
                        Text { text: "0"; font.pixelSize: 11; color: Style.textTertiary }
                        Item { Layout.fillWidth: true }
                        Text { text: topP.toFixed(2); font.pixelSize: 13; font.bold: true; color: Style.primary }
                        Item { Layout.fillWidth: true }
                        Text { text: "1"; font.pixelSize: 11; color: Style.textTertiary }
                    }

                    Slider {
                        Layout.fillWidth: true
                        from: 0; to: 1; stepSize: 0.05
                        value: topP
                        onMoved: topP = value
                        palette.dark: Style.primary
                        palette.midlight: Style.fieldBackground
                    }
                }
            }

            // Top K
            ParameterCard {
                Layout.fillWidth: true
                paramLabel: "Top K"
                paramDescription: "Limit sampling to the K most likely tokens."

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    SpinBox {
                        id: topKSpin
                        from: 1; to: 100; stepSize: 1
                        value: topK
                        onValueModified: topK = value
                        editable: true
                        Layout.preferredWidth: 120

                        contentItem: TextInput {
                            text: topKSpin.textFromValue(topKSpin.value, topKSpin.locale)
                            font.pixelSize: 13
                            color: Style.textPrimary
                            selectionColor: Style.primary
                            selectedTextColor: Style.textPrimary
                            horizontalAlignment: Qt.AlignHCenter
                            verticalAlignment: Qt.AlignVCenter
                            readOnly: !topKSpin.editable
                            validator: topKSpin.validator
                            inputMethodHints: Qt.ImhDigitsOnly
                        }

                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 4
                        }
                    }
                }
            }

            // Frequency Penalty
            ParameterCard {
                Layout.fillWidth: true
                paramLabel: "Frequency Penalty"
                paramDescription: "Penalizes tokens based on how frequently they appear. Reduces repetition."

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    RowLayout {
                        Layout.fillWidth: true
                        Text { text: "-2"; font.pixelSize: 11; color: Style.textTertiary }
                        Item { Layout.fillWidth: true }
                        Text { text: frequencyPenalty.toFixed(1); font.pixelSize: 13; font.bold: true; color: Style.primary }
                        Item { Layout.fillWidth: true }
                        Text { text: "2"; font.pixelSize: 11; color: Style.textTertiary }
                    }

                    Slider {
                        Layout.fillWidth: true
                        from: -2; to: 2; stepSize: 0.1
                        value: frequencyPenalty
                        onMoved: frequencyPenalty = value
                        palette.dark: Style.primary
                        palette.midlight: Style.fieldBackground
                    }
                }
            }

            // Presence Penalty
            ParameterCard {
                Layout.fillWidth: true
                paramLabel: "Presence Penalty"
                paramDescription: "Penalizes tokens based on whether they have appeared at all. Encourages new topics."

                ColumnLayout {
                    Layout.fillWidth: true
                    spacing: 4

                    RowLayout {
                        Layout.fillWidth: true
                        Text { text: "-2"; font.pixelSize: 11; color: Style.textTertiary }
                        Item { Layout.fillWidth: true }
                        Text { text: presencePenalty.toFixed(1); font.pixelSize: 13; font.bold: true; color: Style.primary }
                        Item { Layout.fillWidth: true }
                        Text { text: "2"; font.pixelSize: 11; color: Style.textTertiary }
                    }

                    Slider {
                        Layout.fillWidth: true
                        from: -2; to: 2; stepSize: 0.1
                        value: presencePenalty
                        onMoved: presencePenalty = value
                        palette.dark: Style.primary
                        palette.midlight: Style.fieldBackground
                    }
                }
            }

            // System Prompt
            ParameterCard {
                Layout.fillWidth: true
                Layout.preferredHeight: 180
                paramLabel: "System Prompt"
                paramDescription: "Set a system-level instruction for all agents in this report."

                ScrollView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true

                    TextArea {
                        id: sysPromptArea
                        text: systemPrompt
                        onTextChanged: systemPrompt = text
                        placeholderText: "Optional system prompt..."
                        placeholderTextColor: Style.textHint
                        color: Style.textPrimary
                        font.pixelSize: 13
                        wrapMode: TextEdit.Wrap
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: sysPromptArea.activeFocus ? Style.primary : Style.fieldBorder
                            border.width: 1
                            radius: 4
                        }
                        padding: 8
                    }
                }
            }

            // Toggles row
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: togglesCol.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: togglesCol
                    anchors.left: parent.left
                    anchors.right: parent.right
                    anchors.top: parent.top
                    anchors.margins: 16
                    spacing: 16

                    // Response Format JSON
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2

                            Text {
                                text: "Response Format JSON"
                                font.pixelSize: 13
                                color: Style.textPrimary
                            }

                            Text {
                                text: "Request structured JSON output from the model"
                                font.pixelSize: 11
                                color: Style.textTertiary
                            }
                        }

                        Switch {
                            checked: responseFormatJson
                            onToggled: responseFormatJson = checked
                            palette.highlight: Style.primary
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 1
                        color: Style.cardBorder
                    }

                    // Search toggle
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2

                            Text {
                                text: "Web Search"
                                font.pixelSize: 13
                                color: Style.textPrimary
                            }

                            Text {
                                text: "Enable web search for providers that support it"
                                font.pixelSize: 11
                                color: Style.textTertiary
                            }
                        }

                        Switch {
                            checked: searchEnabled
                            onToggled: searchEnabled = checked
                            palette.highlight: Style.primary
                        }
                    }
                }
            }

            // Action buttons
            RowLayout {
                Layout.fillWidth: true
                Layout.topMargin: 8
                spacing: 12

                Item { Layout.fillWidth: true }

                // Reset button
                Rectangle {
                    Layout.preferredWidth: resetText.implicitWidth + 32
                    Layout.preferredHeight: 40
                    radius: 6
                    color: resetMa.containsMouse ? Style.surfaceVariant : Style.fieldBackground
                    border.color: Style.fieldBorder
                    border.width: 1

                    Text {
                        id: resetText
                        anchors.centerIn: parent
                        text: "Reset Defaults"
                        font.pixelSize: 13
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: resetMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: resetDefaults()
                    }
                }

                // Apply button
                Rectangle {
                    Layout.preferredWidth: applyText.implicitWidth + 32
                    Layout.preferredHeight: 40
                    radius: 6
                    color: applyMa.containsMouse ? Style.secondary : Style.primary

                    Text {
                        id: applyText
                        anchors.centerIn: parent
                        text: "Apply"
                        font.pixelSize: 13
                        font.bold: true
                        color: Style.textPrimary
                    }

                    MouseArea {
                        id: applyMa
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        hoverEnabled: true
                        onClicked: root.applied()
                    }
                }
            }

            Item { Layout.preferredHeight: 20 }
        }
    }

    // Reusable parameter card component
    component ParameterCard: Rectangle {
        property string paramLabel
        property string paramDescription
        default property alias content: cardContent.data

        color: Style.cardBackground
        border.color: Style.cardBorder
        border.width: 1
        radius: 8
        implicitHeight: cardLayout.implicitHeight + 32

        ColumnLayout {
            id: cardLayout
            anchors.left: parent.left
            anchors.right: parent.right
            anchors.top: parent.top
            anchors.margins: 16
            spacing: 8

            Text {
                text: paramLabel
                font.pixelSize: 13
                font.bold: true
                color: Style.textPrimary
            }

            Text {
                text: paramDescription
                font.pixelSize: 11
                color: Style.textTertiary
                wrapMode: Text.Wrap
                Layout.fillWidth: true
            }

            ColumnLayout {
                id: cardContent
                Layout.fillWidth: true
                spacing: 4
            }
        }
    }
}
