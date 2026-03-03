import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    color: Style.background

    property var systemPrompts: []
    property bool showEditor: false
    property var editingPrompt: null

    Component.onCompleted: refresh()

    Connections {
        target: viewModel
        function onSettingsChanged() { refresh() }
    }

    function refresh() {
        systemPrompts = viewModel.getSystemPrompts()
    }

    function newSystemPrompt() {
        editingPrompt = {
            id: "",
            name: "",
            prompt: ""
        }
        showEditor = true
    }

    function editSystemPrompt(prompt) {
        editingPrompt = JSON.parse(JSON.stringify(prompt))
        showEditor = true
    }

    // Delete confirmation
    Dialog {
        id: deleteDialog
        property string promptId: ""
        property string promptName: ""

        anchors.centerIn: parent
        title: "Delete System Prompt"
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel

        background: Rectangle {
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8
        }

        contentItem: Text {
            text: "Delete system prompt \"" + deleteDialog.promptName + "\"?"
            color: Style.textPrimary
            font.pixelSize: 14
            wrapMode: Text.Wrap
        }

        onAccepted: {
            viewModel.deleteSystemPrompt(promptId)
            refresh()
        }
    }

    // Editor overlay
    Rectangle {
        anchors.fill: parent
        visible: showEditor
        color: Style.background
        z: 10

        ScrollView {
            anchors.fill: parent
            anchors.margins: 20
            contentWidth: availableWidth

            ColumnLayout {
                width: parent.width
                spacing: 16

                RowLayout {
                    spacing: 12

                    Button {
                        text: "< Back"
                        onClicked: showEditor = false
                        background: Rectangle { color: "transparent" }
                        contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 14 }
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }

                    Text {
                        text: (editingPrompt && editingPrompt.id) ? "Edit System Prompt" : "New System Prompt"
                        font.pixelSize: 24
                        font.bold: true
                        color: Style.primary
                    }
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: editorCol.implicitHeight + 40
                    color: Style.cardBackground
                    border.color: Style.cardBorder
                    border.width: 1
                    radius: 8

                    ColumnLayout {
                        id: editorCol
                        anchors.fill: parent
                        anchors.margins: 20
                        spacing: 12

                        // Name
                        Text { text: "Name"; font.pixelSize: 13; color: Style.textSecondary }
                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: editingPrompt ? (editingPrompt.name || "") : ""
                            placeholderText: "System prompt name"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                            onTextChanged: if (editingPrompt) editingPrompt.name = text
                        }

                        // Prompt text (large)
                        Text { text: "System Prompt Text"; font.pixelSize: 13; color: Style.textSecondary }
                        ScrollView {
                            Layout.fillWidth: true
                            Layout.preferredHeight: 300
                            Layout.minimumHeight: 200

                            TextArea {
                                id: promptTextArea
                                text: editingPrompt ? (editingPrompt.prompt || "") : ""
                                placeholderText: "Enter system prompt text...\n\nThis will be sent as the system message to the AI model."
                                color: Style.textPrimary
                                placeholderTextColor: Style.textHint
                                font.pixelSize: 14
                                wrapMode: TextEdit.Wrap
                                background: Rectangle { color: Style.fieldBackground; border.color: parent.activeFocus ? Style.primary : Style.fieldBorder; border.width: 1; radius: 6 }
                                onTextChanged: if (editingPrompt) editingPrompt.prompt = text
                            }
                        }

                        // Character count
                        Text {
                            text: (editingPrompt && editingPrompt.prompt ? editingPrompt.prompt.length : 0) + " characters"
                            font.pixelSize: 11
                            color: Style.textTertiary
                        }

                        // Save
                        RowLayout {
                            Layout.topMargin: 8
                            spacing: 12

                            Button {
                                text: "Save System Prompt"
                                onClicked: {
                                    viewModel.saveSystemPrompt(editingPrompt)
                                    showEditor = false
                                    refresh()
                                }
                                background: Rectangle { color: Style.primary; radius: 6 }
                                contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 14; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                leftPadding: 20; rightPadding: 20; topPadding: 8; bottomPadding: 8
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Cancel"
                                onClicked: showEditor = false
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.fieldBorder; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.textSecondary; font.pixelSize: 14; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                leftPadding: 20; rightPadding: 20; topPadding: 8; bottomPadding: 8
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }
                    }
                }

                Item { Layout.fillHeight: true }
            }
        }
    }

    // List view
    Item {
        anchors.fill: parent
        visible: !showEditor

        ScrollView {
            anchors.fill: parent
            anchors.margins: 20
            contentWidth: availableWidth

            ColumnLayout {
                width: parent.width
                spacing: 16

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 12

                    Text {
                        text: "System Prompts"
                        font.pixelSize: 28
                        font.bold: true
                        color: Style.primary
                        Layout.fillWidth: true
                    }

                    Button {
                        text: "+ Add System Prompt"
                        onClicked: newSystemPrompt()
                        background: Rectangle { color: Style.primary; radius: 6 }
                        contentItem: Text { text: parent.text; color: "#FFFFFF"; font.pixelSize: 13; font.bold: true; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                        leftPadding: 16; rightPadding: 16; topPadding: 6; bottomPadding: 6
                        MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                    }
                }

                Text {
                    visible: systemPrompts.length === 0
                    text: "No system prompts configured. System prompts define the AI's behavior and personality."
                    font.pixelSize: 14
                    color: Style.textSecondary
                }

                Repeater {
                    model: systemPrompts.length

                    Rectangle {
                        required property int index

                        Layout.fillWidth: true
                        implicitHeight: spRow.implicitHeight + 24
                        color: spMouse.containsMouse ? Qt.lighter(Style.cardBackground, 1.15) : Style.cardBackground
                        border.color: spMouse.containsMouse ? Style.primary : Style.cardBorder
                        border.width: 1
                        radius: 8

                        property var sp: systemPrompts[index]

                        RowLayout {
                            id: spRow
                            anchors.fill: parent
                            anchors.margins: 12
                            spacing: 12

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    text: sp.name || "(unnamed)"
                                    font.pixelSize: 15
                                    font.bold: true
                                    color: Style.textPrimary
                                }

                                Text {
                                    text: (sp.prompt || "").substring(0, 100) + ((sp.prompt || "").length > 100 ? "..." : "")
                                    font.pixelSize: 12
                                    color: Style.textSecondary
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }

                                Text {
                                    text: (sp.prompt || "").length + " characters"
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                }
                            }

                            Button {
                                text: "Edit"
                                onClicked: editSystemPrompt(sp)
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.primary; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.primary; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }

                            Button {
                                text: "Delete"
                                onClicked: {
                                    deleteDialog.promptId = sp.id
                                    deleteDialog.promptName = sp.name
                                    deleteDialog.open()
                                }
                                background: Rectangle { color: Style.fieldBackground; border.color: Style.error; border.width: 1; radius: 6 }
                                contentItem: Text { text: parent.text; color: Style.error; font.pixelSize: 12; horizontalAlignment: Text.AlignHCenter; verticalAlignment: Text.AlignVCenter }
                                MouseArea { anchors.fill: parent; cursorShape: Qt.PointingHandCursor; onClicked: parent.clicked() }
                            }
                        }

                        MouseArea {
                            id: spMouse
                            anchors.fill: parent
                            hoverEnabled: true
                            propagateComposedEvents: true
                            z: -1
                        }
                    }
                }

                Item { Layout.fillHeight: true }
            }
        }
    }
}
