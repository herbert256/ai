import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var generalSettings: ({})
    property bool showHfKey: false
    property bool showOrKey: false

    Component.onCompleted: {
        generalSettings = viewModel.getGeneralSettings()
    }

    Connections {
        target: viewModel
        function onExportCompleted(success, message) {
            statusText.text = message
            statusText.color = success ? Style.success : Style.error
            statusTimer.restart()
        }
        function onImportCompleted(success, message) {
            statusText.text = message
            statusText.color = success ? Style.success : Style.error
            if (success) generalSettings = viewModel.getGeneralSettings()
            statusTimer.restart()
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
                text: "Settings"
                font.pixelSize: 28
                font.bold: true
                color: Style.primary
            }

            // Status message
            Text {
                id: statusText
                text: ""
                font.pixelSize: 13
                color: Style.success
                visible: text.length > 0
            }

            Timer {
                id: statusTimer
                interval: 5000
                onTriggered: statusText.text = ""
            }

            // ── General Settings ──
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: generalCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: generalCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: "General Settings"
                        font.pixelSize: 18
                        font.bold: true
                        color: Style.textPrimary
                    }

                    // User Name
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 4

                        Text {
                            text: "User Name"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 400
                            text: generalSettings.userName || ""
                            placeholderText: "Enter your name"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: parent.activeFocus ? Style.primary : Style.fieldBorder
                                border.width: 1
                                radius: 6
                            }
                            onTextChanged: {
                                generalSettings.userName = text
                            }
                            onEditingFinished: saveGeneral()
                        }
                    }

                    // Default Email
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 4

                        Text {
                            text: "Default Email"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 400
                            text: generalSettings.defaultEmail || ""
                            placeholderText: "email@example.com"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: parent.activeFocus ? Style.primary : Style.fieldBorder
                                border.width: 1
                                radius: 6
                            }
                            onTextChanged: {
                                generalSettings.defaultEmail = text
                            }
                            onEditingFinished: saveGeneral()
                        }
                    }

                    // Developer Mode
                    RowLayout {
                        spacing: 12

                        Text {
                            text: "Developer Mode"
                            font.pixelSize: 14
                            color: Style.textPrimary
                        }

                        Switch {
                            checked: generalSettings.developerMode || false
                            onToggled: {
                                generalSettings.developerMode = checked
                                saveGeneral()
                            }
                        }
                    }
                }
            }

            // ── Global API Keys ──
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: apiKeysCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: apiKeysCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: "Global API Keys"
                        font.pixelSize: 18
                        font.bold: true
                        color: Style.textPrimary
                    }

                    // HuggingFace API Key
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 4

                        Text {
                            text: "HuggingFace API Key"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8

                            TextField {
                                id: hfKeyField
                                Layout.fillWidth: true
                                Layout.maximumWidth: 400
                                text: generalSettings.huggingFaceApiKey || ""
                                echoMode: showHfKey ? TextInput.Normal : TextInput.Password
                                placeholderText: "hf_..."
                                color: Style.textPrimary
                                placeholderTextColor: Style.textHint
                                font.pixelSize: 14
                                background: Rectangle {
                                    color: Style.fieldBackground
                                    border.color: parent.activeFocus ? Style.primary : Style.fieldBorder
                                    border.width: 1
                                    radius: 6
                                }
                                onTextChanged: {
                                    generalSettings.huggingFaceApiKey = text
                                }
                                onEditingFinished: saveGeneral()
                            }

                            Button {
                                text: showHfKey ? "Hide" : "Show"
                                onClicked: showHfKey = !showHfKey
                                background: Rectangle {
                                    color: Style.fieldBackground
                                    border.color: Style.fieldBorder
                                    border.width: 1
                                    radius: 6
                                }
                                contentItem: Text {
                                    text: parent.text
                                    color: Style.textSecondary
                                    font.pixelSize: 12
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                }
                            }
                        }
                    }

                    // OpenRouter API Key
                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 4

                        Text {
                            text: "OpenRouter API Key"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: 8

                            TextField {
                                id: orKeyField
                                Layout.fillWidth: true
                                Layout.maximumWidth: 400
                                text: generalSettings.openRouterApiKey || ""
                                echoMode: showOrKey ? TextInput.Normal : TextInput.Password
                                placeholderText: "sk-or-..."
                                color: Style.textPrimary
                                placeholderTextColor: Style.textHint
                                font.pixelSize: 14
                                background: Rectangle {
                                    color: Style.fieldBackground
                                    border.color: parent.activeFocus ? Style.primary : Style.fieldBorder
                                    border.width: 1
                                    radius: 6
                                }
                                onTextChanged: {
                                    generalSettings.openRouterApiKey = text
                                }
                                onEditingFinished: saveGeneral()
                            }

                            Button {
                                text: showOrKey ? "Hide" : "Show"
                                onClicked: showOrKey = !showOrKey
                                background: Rectangle {
                                    color: Style.fieldBackground
                                    border.color: Style.fieldBorder
                                    border.width: 1
                                    radius: 6
                                }
                                contentItem: Text {
                                    text: parent.text
                                    color: Style.textSecondary
                                    font.pixelSize: 12
                                    horizontalAlignment: Text.AlignHCenter
                                    verticalAlignment: Text.AlignVCenter
                                }
                            }
                        }
                    }
                }
            }

            // ── Export / Import ──
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: exportCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: exportCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: "Export / Import"
                        font.pixelSize: 18
                        font.bold: true
                        color: Style.textPrimary
                    }

                    GridLayout {
                        columns: 2
                        columnSpacing: 12
                        rowSpacing: 12

                        ActionButton {
                            text: "Export Settings"
                            onClicked: viewModel.exportSettings()
                        }

                        ActionButton {
                            text: "Import Settings"
                            onClicked: viewModel.importSettings()
                        }

                        ActionButton {
                            text: "Export API Keys"
                            onClicked: viewModel.exportApiKeys()
                        }

                        ActionButton {
                            text: "Import API Keys"
                            onClicked: viewModel.importApiKeys()
                        }
                    }
                }
            }

            // ── About ──
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: aboutCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: aboutCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 8

                    Text {
                        text: "About"
                        font.pixelSize: 18
                        font.bold: true
                        color: Style.textPrimary
                    }

                    Text {
                        text: "AI - Linux Desktop"
                        font.pixelSize: 14
                        color: Style.textPrimary
                    }

                    Text {
                        text: "Multi-platform AI-powered reports and chat"
                        font.pixelSize: 13
                        color: Style.textSecondary
                    }

                    Text {
                        text: "Version 1.0.0 (Qt6)"
                        font.pixelSize: 13
                        color: Style.textSecondary
                    }

                    Text {
                        text: "31 AI services supported"
                        font.pixelSize: 13
                        color: Style.textSecondary
                    }

                    Text {
                        text: "Export format: v21"
                        font.pixelSize: 12
                        color: Style.textTertiary
                    }
                }
            }

            Item { Layout.fillHeight: true }
        }
    }

    function saveGeneral() {
        viewModel.saveGeneralSettings(generalSettings)
    }

    component ActionButton: Button {
        implicitWidth: 160
        implicitHeight: 36

        background: Rectangle {
            color: parent.hovered ? Style.surfaceVariant : Style.fieldBackground
            border.color: Style.primary
            border.width: 1
            radius: 6
        }

        contentItem: Text {
            text: parent.text
            color: Style.primary
            font.pixelSize: 13
            horizontalAlignment: Text.AlignHCenter
            verticalAlignment: Text.AlignVCenter
        }

        MouseArea {
            anchors.fill: parent
            cursorShape: Qt.PointingHandCursor
            onClicked: parent.clicked()
        }
    }
}
