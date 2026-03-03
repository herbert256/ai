import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    property string providerId: ""
    property var onBackClicked: function() {}

    property var config: ({})
    property var providerInfo: ({})
    property var modelList: []
    property var endpoints: []
    property bool showApiKey: false
    property string testResult: ""
    property color testResultColor: Style.textSecondary
    property bool isFetchingModels: false
    property bool isModelSourceApi: true
    property string customModelsText: ""

    Component.onCompleted: loadProviderData()

    Connections {
        target: viewModel
        function onModelsLoaded(pid, models) {
            if (pid === providerId) {
                modelList = models
                isFetchingModels = false
                loadProviderData()
            }
        }
        function onModelTestResult(pid, error) {
            if (pid === providerId) {
                if (error.length === 0) {
                    testResult = "Connection successful"
                    testResultColor = Style.success
                } else {
                    testResult = "Error: " + error
                    testResultColor = Style.error
                }
            }
        }
    }

    function loadProviderData() {
        var providers = viewModel.getProviders()
        for (var i = 0; i < providers.length; i++) {
            if (providers[i].id === providerId) {
                providerInfo = providers[i]
                break
            }
        }
        config = viewModel.getProviderConfig(providerId)
        modelList = viewModel.getProviderModels(providerId)
        endpoints = viewModel.getEndpoints(providerId)
        isModelSourceApi = (config.modelSource || "API") === "API"
        if (!isModelSourceApi && config.models) {
            customModelsText = config.models.join(", ")
        }
    }

    ScrollView {
        anchors.fill: parent
        anchors.margins: 20
        contentWidth: availableWidth

        ColumnLayout {
            width: parent.width
            spacing: 16

            // Back button + title
            RowLayout {
                spacing: 12

                Button {
                    text: "< Back"
                    onClicked: root.onBackClicked()
                    background: Rectangle {
                        color: parent.hovered ? Style.surfaceVariant : "transparent"
                        radius: 6
                    }
                    contentItem: Text {
                        text: parent.text
                        color: Style.primary
                        font.pixelSize: 14
                    }

                    MouseArea {
                        anchors.fill: parent
                        cursorShape: Qt.PointingHandCursor
                        onClicked: parent.clicked()
                    }
                }

                Text {
                    text: providerInfo.displayName || providerId
                    font.pixelSize: 28
                    font.bold: true
                    color: Style.primary
                }

                Rectangle {
                    width: 12
                    height: 12
                    radius: 6
                    color: Style.colorForState(providerInfo.state || "not-used")
                }

                Item { Layout.fillWidth: true }
            }

            // ── API Key ──
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: apiKeyCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: apiKeyCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: "API Key"
                        font.pixelSize: 16
                        font.bold: true
                        color: Style.textPrimary
                    }

                    RowLayout {
                        Layout.fillWidth: true
                        spacing: 8

                        TextField {
                            id: apiKeyField
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: config.apiKey || ""
                            echoMode: showApiKey ? TextInput.Normal : TextInput.Password
                            placeholderText: "Enter API key"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: parent.activeFocus ? Style.primary : Style.fieldBorder
                                border.width: 1
                                radius: 6
                            }
                            onEditingFinished: {
                                viewModel.setProviderApiKey(providerId, text)
                            }
                        }

                        Button {
                            text: showApiKey ? "Hide" : "Show"
                            onClicked: showApiKey = !showApiKey
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

                    // Admin URL
                    Text {
                        visible: (providerInfo.adminUrl || "").length > 0
                        text: "Admin: " + (providerInfo.adminUrl || "")
                        font.pixelSize: 12
                        color: Style.info
                        font.underline: true

                        MouseArea {
                            anchors.fill: parent
                            cursorShape: Qt.PointingHandCursor
                            onClicked: viewModel.openUrl(providerInfo.adminUrl)
                        }
                    }
                }
            }

            // ── Model ──
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: modelCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: modelCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: "Model"
                        font.pixelSize: 16
                        font.bold: true
                        color: Style.textPrimary
                    }

                    // Model source toggle
                    RowLayout {
                        spacing: 12

                        Text {
                            text: "Model Source"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        Button {
                            text: "API"
                            highlighted: isModelSourceApi
                            onClicked: {
                                isModelSourceApi = true
                            }
                            background: Rectangle {
                                color: isModelSourceApi ? Style.primary : Style.fieldBackground
                                border.color: Style.primary
                                border.width: 1
                                radius: 6
                            }
                            contentItem: Text {
                                text: parent.text
                                color: isModelSourceApi ? "#FFFFFF" : Style.textSecondary
                                font.pixelSize: 12
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }
                        }

                        Button {
                            text: "Manual"
                            highlighted: !isModelSourceApi
                            onClicked: {
                                isModelSourceApi = false
                            }
                            background: Rectangle {
                                color: !isModelSourceApi ? Style.primary : Style.fieldBackground
                                border.color: Style.primary
                                border.width: 1
                                radius: 6
                            }
                            contentItem: Text {
                                text: parent.text
                                color: !isModelSourceApi ? "#FFFFFF" : Style.textSecondary
                                font.pixelSize: 12
                                horizontalAlignment: Text.AlignHCenter
                                verticalAlignment: Text.AlignVCenter
                            }
                        }
                    }

                    // Model selector (API mode)
                    ColumnLayout {
                        visible: isModelSourceApi
                        Layout.fillWidth: true
                        spacing: 8

                        ComboBox {
                            id: modelCombo
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            model: modelList
                            currentIndex: {
                                var current = config.model || ""
                                for (var i = 0; i < modelList.length; i++) {
                                    if (modelList[i] === current) return i
                                }
                                return -1
                            }
                            onActivated: {
                                viewModel.setProviderModel(providerId, modelList[currentIndex])
                                loadProviderData()
                            }

                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: Style.fieldBorder
                                border.width: 1
                                radius: 6
                            }

                            contentItem: Text {
                                text: modelCombo.displayText
                                color: Style.textPrimary
                                font.pixelSize: 14
                                verticalAlignment: Text.AlignVCenter
                                leftPadding: 8
                            }

                            popup.background: Rectangle {
                                color: Style.cardBackground
                                border.color: Style.cardBorder
                                border.width: 1
                                radius: 6
                            }

                            delegate: ItemDelegate {
                                width: modelCombo.width
                                contentItem: Text {
                                    text: modelData
                                    color: Style.textPrimary
                                    font.pixelSize: 13
                                    elide: Text.ElideRight
                                }
                                background: Rectangle {
                                    color: parent.highlighted ? Style.surfaceVariant : Style.cardBackground
                                }
                            }
                        }

                        Text {
                            text: modelList.length + " models available"
                            font.pixelSize: 12
                            color: Style.textTertiary
                        }
                    }

                    // Custom model list (Manual mode)
                    ColumnLayout {
                        visible: !isModelSourceApi
                        Layout.fillWidth: true
                        spacing: 8

                        Text {
                            text: "Custom Models (comma-separated)"
                            font.pixelSize: 13
                            color: Style.textSecondary
                        }

                        TextField {
                            Layout.fillWidth: true
                            Layout.maximumWidth: 500
                            text: customModelsText
                            placeholderText: "model-1, model-2, model-3"
                            color: Style.textPrimary
                            placeholderTextColor: Style.textHint
                            font.pixelSize: 14
                            background: Rectangle {
                                color: Style.fieldBackground
                                border.color: parent.activeFocus ? Style.primary : Style.fieldBorder
                                border.width: 1
                                radius: 6
                            }
                            onEditingFinished: {
                                var models = text.split(",").map(function(s) { return s.trim() }).filter(function(s) { return s.length > 0 })
                                viewModel.setProviderModels(providerId, models)
                            }
                        }
                    }

                    // Fetch models button
                    RowLayout {
                        spacing: 12

                        Button {
                            text: isFetchingModels ? "Fetching..." : "Fetch Models"
                            enabled: !isFetchingModels
                            onClicked: {
                                isFetchingModels = true
                                viewModel.fetchModels(providerId)
                            }
                            background: Rectangle {
                                color: parent.hovered ? Style.surfaceVariant : Style.fieldBackground
                                border.color: Style.primary
                                border.width: 1
                                radius: 6
                                opacity: parent.enabled ? 1.0 : 0.5
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
                                enabled: parent.enabled
                                onClicked: parent.clicked()
                            }
                        }
                    }
                }
            }

            // ── Endpoints ──
            Rectangle {
                visible: endpoints.length > 0
                Layout.fillWidth: true
                implicitHeight: endpointCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: endpointCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: "Endpoints"
                        font.pixelSize: 16
                        font.bold: true
                        color: Style.textPrimary
                    }

                    Repeater {
                        model: endpoints.length

                        RowLayout {
                            required property int index
                            Layout.fillWidth: true
                            spacing: 8

                            property var ep: endpoints[index]

                            RadioButton {
                                checked: ep.isDefault || false
                                onClicked: {
                                    var updated = []
                                    for (var i = 0; i < endpoints.length; i++) {
                                        var e = Object.assign({}, endpoints[i])
                                        e.isDefault = (i === index)
                                        updated.push(e)
                                    }
                                    viewModel.saveEndpoints(providerId, updated)
                                    loadProviderData()
                                }
                            }

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2

                                Text {
                                    text: ep.name || ""
                                    font.pixelSize: 13
                                    color: Style.textPrimary
                                }

                                Text {
                                    text: ep.url || ""
                                    font.pixelSize: 11
                                    color: Style.textTertiary
                                    elide: Text.ElideRight
                                    Layout.fillWidth: true
                                }
                            }
                        }
                    }
                }
            }

            // ── Test Connection ──
            Rectangle {
                Layout.fillWidth: true
                implicitHeight: testCol.implicitHeight + 40
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    id: testCol
                    anchors.fill: parent
                    anchors.margins: 20
                    spacing: 12

                    Text {
                        text: "Test Connection"
                        font.pixelSize: 16
                        font.bold: true
                        color: Style.textPrimary
                    }

                    RowLayout {
                        spacing: 12

                        Button {
                            text: "Test Connection"
                            onClicked: {
                                testResult = "Testing..."
                                testResultColor = Style.textSecondary
                                viewModel.testProvider(providerId)
                            }
                            background: Rectangle {
                                color: parent.hovered ? Style.surfaceVariant : Style.fieldBackground
                                border.color: Style.success
                                border.width: 1
                                radius: 6
                            }
                            contentItem: Text {
                                text: parent.text
                                color: Style.success
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

                        Text {
                            visible: testResult.length > 0
                            text: testResult
                            font.pixelSize: 13
                            color: testResultColor
                            wrapMode: Text.Wrap
                            Layout.fillWidth: true
                        }
                    }
                }
            }

            Item { Layout.fillHeight: true }
        }
    }
}
