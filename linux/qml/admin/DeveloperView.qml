import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property var providers: []
    property var modelList: []
    property string selectedProviderId: ""
    property string testResult: ""
    property bool isTesting: false
    property bool showRawSettings: false
    property string rawSettingsJson: ""

    Component.onCompleted: {
        providers = viewModel.getProviders() || []
        if (providers.length > 0) {
            selectedProviderId = providers[0].id
            modelList = viewModel.getProviderModels(selectedProviderId) || []
        }
    }

    Connections {
        target: viewModel

        function onTestResult(result) {
            root.testResult = result
            root.isTesting = false
        }

        function onTestError(error) {
            root.testResult = "Error: " + error
            root.isTesting = false
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
                text: "Developer"
                font.pixelSize: 24
                font.bold: true
                color: Style.primary
            }

            Text {
                text: "Developer tools and diagnostics"
                font.pixelSize: 13
                color: Style.textSecondary
            }

            // Provider registry info
            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: registryCol.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: registryCol
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 8

                    Text {
                        text: "Provider Registry"
                        font.pixelSize: 14
                        font.bold: true
                        color: Style.textPrimary
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8

                        Text {
                            text: "Registered providers:"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        Text {
                            text: root.providers.length.toString()
                            font.pixelSize: 13
                            font.bold: true
                            color: Style.primary
                        }
                    }
                }
            }

            // Quick test section
            Text {
                text: "Quick Test"
                font.pixelSize: 16
                font.bold: true
                color: Style.textPrimary
                Layout.topMargin: 8
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: testCol.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: testCol
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    // Provider selection
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Text {
                            text: "Provider"
                            font.pixelSize: 12
                            color: Style.textSecondary
                            Layout.preferredWidth: 70
                        }

                        ComboBox {
                            id: testProviderCombo
                            Layout.fillWidth: true
                            model: {
                                var names = []
                                for (var i = 0; i < root.providers.length; i++)
                                    names.push(root.providers[i].displayName || root.providers[i].id)
                                return names
                            }
                            onCurrentIndexChanged: {
                                if (currentIndex >= 0 && currentIndex < root.providers.length) {
                                    root.selectedProviderId = root.providers[currentIndex].id
                                    root.modelList = viewModel.getProviderModels(root.selectedProviderId) || []
                                    if (root.modelList.length > 0)
                                        testModelField.text = root.modelList[0]
                                }
                            }
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: Style.fieldBorder
                                border.width: 1
                                radius: 4
                                implicitHeight: 30
                            }
                            contentItem: Text {
                                text: testProviderCombo.displayText
                                color: Style.textPrimary
                                font.pixelSize: 12
                                verticalAlignment: Text.AlignVCenter
                                leftPadding: 8
                            }
                            popup.background: Rectangle {
                                color: Style.cardBackground
                                border.color: Style.cardBorder
                                border.width: 1
                                radius: 4
                            }
                            delegate: ItemDelegate {
                                width: testProviderCombo.width
                                contentItem: Text {
                                    text: modelData
                                    color: Style.textPrimary
                                    font.pixelSize: 12
                                    verticalAlignment: Text.AlignVCenter
                                }
                                background: Rectangle {
                                    color: highlighted ? Style.surfaceVariant : Style.cardBackground
                                }
                                highlighted: testProviderCombo.highlightedIndex === index
                            }
                        }
                    }

                    // Model field
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Text {
                            text: "Model"
                            font.pixelSize: 12
                            color: Style.textSecondary
                            Layout.preferredWidth: 70
                        }

                        TextField {
                            id: testModelField
                            Layout.fillWidth: true
                            placeholderText: "Enter model name..."
                            placeholderTextColor: Style.textHint
                            color: Style.textPrimary
                            font.pixelSize: 12
                            text: root.modelList.length > 0 ? root.modelList[0] : ""
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: testModelField.activeFocus ? Style.primary : Style.fieldBorder
                                border.width: 1
                                radius: 4
                                implicitHeight: 30
                            }
                            padding: 8
                        }
                    }

                    // Prompt area
                    Text {
                        text: "Prompt"
                        font.pixelSize: 12
                        color: Style.textSecondary
                    }

                    ScrollView {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 100

                        TextArea {
                            id: testPromptArea
                            placeholderText: "Enter a test prompt..."
                            placeholderTextColor: Style.textHint
                            color: Style.textPrimary
                            font.pixelSize: 12
                            wrapMode: TextEdit.Wrap
                            text: "Say hello in one sentence."
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: testPromptArea.activeFocus ? Style.primary : Style.fieldBorder
                                border.width: 1
                                radius: 4
                            }
                            padding: 8
                        }
                    }

                    // Test button
                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Rectangle {
                            Layout.preferredWidth: testBtnText.implicitWidth + 24
                            Layout.preferredHeight: 36
                            radius: 6
                            color: {
                                if (root.isTesting) return Style.fieldBackground
                                return testBtnMa.containsMouse ? Style.secondary : Style.primary
                            }

                            Text {
                                id: testBtnText
                                anchors.centerIn: parent
                                text: root.isTesting ? "Testing..." : "Test"
                                font.pixelSize: 12
                                font.bold: true
                                color: root.isTesting ? Style.textHint : "#FFFFFF"
                            }

                            MouseArea {
                                id: testBtnMa
                                anchors.fill: parent
                                cursorShape: root.isTesting ? Qt.WaitCursor : Qt.PointingHandCursor
                                hoverEnabled: true
                                enabled: !root.isTesting
                                onClicked: {
                                    if (testPromptArea.text.trim() === "") return
                                    root.isTesting = true
                                    root.testResult = ""
                                    viewModel.testProvider(
                                        root.selectedProviderId,
                                        testModelField.text.trim(),
                                        testPromptArea.text.trim()
                                    )
                                }
                            }
                        }

                        // Streaming dots while testing
                        Row {
                            spacing: 4
                            visible: root.isTesting

                            Repeater {
                                model: 3
                                Rectangle {
                                    width: 6; height: 6; radius: 3
                                    color: Style.primary
                                    opacity: 0.3

                                    SequentialAnimation on opacity {
                                        loops: Animation.Infinite
                                        PauseAnimation { duration: index * 200 }
                                        NumberAnimation { to: 1.0; duration: 400 }
                                        NumberAnimation { to: 0.3; duration: 400 }
                                        PauseAnimation { duration: (2 - index) * 200 }
                                    }
                                }
                            }
                        }

                        Item { Layout.fillWidth: true }
                    }

                    // Result display
                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: Math.max(resultArea.implicitHeight + 16, 80)
                        color: Style.fieldBackground
                        border.color: Style.fieldBorder
                        border.width: 1
                        radius: 4
                        visible: root.testResult !== ""

                        ScrollView {
                            anchors.fill: parent
                            anchors.margins: 8

                            TextArea {
                                id: resultArea
                                text: root.testResult
                                color: root.testResult.startsWith("Error:") ? Style.error : Style.textPrimary
                                font.pixelSize: 12
                                font.family: "monospace"
                                wrapMode: TextEdit.Wrap
                                readOnly: true
                                selectByMouse: true
                                background: Rectangle { color: "transparent" }
                                padding: 0
                            }
                        }
                    }
                }
            }

            // Raw settings viewer
            Text {
                text: "Settings Inspector"
                font.pixelSize: 16
                font.bold: true
                color: Style.textPrimary
                Layout.topMargin: 8
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: settingsViewerCol.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: settingsViewerCol
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Text {
                            text: "View raw settings data"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        Item { Layout.fillWidth: true }

                        Rectangle {
                            Layout.preferredWidth: showSettingsBtnText.implicitWidth + 24
                            Layout.preferredHeight: 32
                            radius: 6
                            color: showSettingsMa.containsMouse ? Style.secondary : Style.primary

                            Text {
                                id: showSettingsBtnText
                                anchors.centerIn: parent
                                text: root.showRawSettings ? "Hide" : "Show Settings"
                                font.pixelSize: 12
                                font.bold: true
                                color: "#FFFFFF"
                            }

                            MouseArea {
                                id: showSettingsMa
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                hoverEnabled: true
                                onClicked: {
                                    if (!root.showRawSettings) {
                                        root.rawSettingsJson = viewModel.getRawSettingsJson() || "{}"
                                    }
                                    root.showRawSettings = !root.showRawSettings
                                }
                            }
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 300
                        color: Style.fieldBackground
                        border.color: Style.fieldBorder
                        border.width: 1
                        radius: 4
                        visible: root.showRawSettings

                        ScrollView {
                            anchors.fill: parent
                            anchors.margins: 4

                            TextArea {
                                text: root.rawSettingsJson
                                color: Style.textPrimary
                                font.pixelSize: 11
                                font.family: "monospace"
                                wrapMode: TextEdit.Wrap
                                readOnly: true
                                selectByMouse: true
                                background: Rectangle { color: "transparent" }
                                padding: 8
                            }
                        }
                    }
                }
            }

            // Clipboard tools
            Text {
                text: "Clipboard Tools"
                font.pixelSize: 16
                font.bold: true
                color: Style.textPrimary
                Layout.topMargin: 8
            }

            Rectangle {
                Layout.fillWidth: true
                Layout.preferredHeight: clipCol.implicitHeight + 32
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: clipCol
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 12

                        Rectangle {
                            Layout.preferredWidth: pasteBtnText.implicitWidth + 24
                            Layout.preferredHeight: 32
                            radius: 6
                            color: pasteBtnMa.containsMouse ? Style.secondary : Style.primary

                            Text {
                                id: pasteBtnText
                                anchors.centerIn: parent
                                text: "Paste from Clipboard"
                                font.pixelSize: 12
                                font.bold: true
                                color: "#FFFFFF"
                            }

                            MouseArea {
                                id: pasteBtnMa
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                hoverEnabled: true
                                onClicked: {
                                    var content = viewModel.getClipboardText() || ""
                                    clipboardArea.text = content
                                }
                            }
                        }

                        Text {
                            text: "Reads text content from system clipboard"
                            font.pixelSize: 11
                            color: Style.textTertiary
                            Layout.fillWidth: true
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        Layout.preferredHeight: 120
                        color: Style.fieldBackground
                        border.color: Style.fieldBorder
                        border.width: 1
                        radius: 4

                        ScrollView {
                            anchors.fill: parent
                            anchors.margins: 4

                            TextArea {
                                id: clipboardArea
                                placeholderText: "Clipboard content will appear here..."
                                placeholderTextColor: Style.textHint
                                color: Style.textPrimary
                                font.pixelSize: 12
                                font.family: "monospace"
                                wrapMode: TextEdit.Wrap
                                readOnly: true
                                selectByMouse: true
                                background: Rectangle { color: "transparent" }
                                padding: 8
                            }
                        }
                    }
                }
            }

            Item { Layout.fillHeight: true }
        }
    }
}
