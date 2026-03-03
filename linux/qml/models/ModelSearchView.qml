import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import "../theme"

Rectangle {
    id: root
    color: Style.background

    // ---- State ----
    property var providers: []
    property string filterProviderId: ""   // "" means all providers
    property string searchQuery: ""
    property var searchResults: []         // Array of {modelName, providerId, providerName}
    property var loadingProviders: ({})    // providerId -> bool
    property string selectedModelName: ""
    property string selectedProviderId: ""
    property bool showDetail: false

    Component.onCompleted: {
        providers = viewModel.getProviders()
        buildFullModelList()
    }

    Connections {
        target: viewModel

        function onModelsLoaded(providerId, models) {
            // Clear loading flag
            var loading = root.loadingProviders
            loading[providerId] = false
            root.loadingProviders = loading

            // Refresh the displayed list
            buildFullModelList()
        }

        function onModelSearchResults(results) {
            root.searchResults = results
        }
    }

    // ---- Functions ----
    function buildFullModelList() {
        var results = []
        for (var i = 0; i < providers.length; i++) {
            var p = providers[i]
            if (filterProviderId !== "" && p.id !== filterProviderId)
                continue

            var models = viewModel.getProviderModels(p.id)
            for (var j = 0; j < models.length; j++) {
                var modelName = models[j]
                if (searchQuery !== "" &&
                    modelName.toLowerCase().indexOf(searchQuery.toLowerCase()) === -1)
                    continue
                results.push({
                    modelName: modelName,
                    providerId: p.id,
                    providerName: p.displayName || p.id
                })
            }
        }
        searchResults = results
    }

    function applyFilter() {
        buildFullModelList()
    }

    function fetchModelsForProvider(providerId) {
        var loading = loadingProviders
        loading[providerId] = true
        loadingProviders = loading
        viewModel.fetchModels(providerId)
    }

    function getProviderState(providerId) {
        var config = viewModel.getProviderConfig(providerId)
        if (!config) return "not-used"
        // Infer state from config
        if (config.apiKey && config.apiKey !== "")
            return "ok"
        return "not-used"
    }

    function selectModel(modelName, providerId) {
        selectedModelName = modelName
        selectedProviderId = providerId
        showDetail = true
    }

    function providerDisplayName(providerId) {
        for (var i = 0; i < providers.length; i++) {
            if (providers[i].id === providerId)
                return providers[i].displayName || providers[i].id
        }
        return providerId
    }

    // Group results by provider for display
    function getGroupedResults() {
        var groups = {}
        var order = []
        for (var i = 0; i < searchResults.length; i++) {
            var r = searchResults[i]
            if (!(r.providerId in groups)) {
                groups[r.providerId] = {
                    providerId: r.providerId,
                    providerName: r.providerName,
                    models: []
                }
                order.push(r.providerId)
            }
            groups[r.providerId].models.push(r.modelName)
        }
        var result = []
        for (var j = 0; j < order.length; j++)
            result.push(groups[order[j]])
        return result
    }

    // ---- Layout ----
    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 16
        spacing: 12

        // Title
        Text {
            text: "Model Search"
            font.pixelSize: 22
            font.bold: true
            color: Style.primary
        }

        // Search bar + filter
        Rectangle {
            Layout.fillWidth: true
            Layout.preferredHeight: searchRow.implicitHeight + 24
            color: Style.cardBackground
            border.color: Style.cardBorder
            border.width: 1
            radius: 8

            RowLayout {
                id: searchRow
                anchors.fill: parent
                anchors.margins: 12
                spacing: 12

                TextField {
                    id: searchField
                    Layout.fillWidth: true
                    placeholderText: "Search models..."
                    placeholderTextColor: Style.textHint
                    color: Style.textPrimary
                    font.pixelSize: 13
                    onTextChanged: {
                        root.searchQuery = text
                        root.applyFilter()
                    }
                    background: Rectangle {
                        color: Style.fieldBackground
                        border.color: searchField.activeFocus ? Style.primary : Style.fieldBorder
                        border.width: 1
                        radius: 4
                        implicitHeight: 32
                    }
                }

                Text {
                    text: "Provider"
                    font.pixelSize: 12
                    color: Style.textSecondary
                }

                ComboBox {
                    id: providerFilterCombo
                    Layout.preferredWidth: 200
                    model: {
                        var items = ["All Providers"]
                        for (var i = 0; i < root.providers.length; i++)
                            items.push(root.providers[i].displayName || root.providers[i].id)
                        return items
                    }
                    onCurrentIndexChanged: {
                        if (currentIndex === 0) {
                            root.filterProviderId = ""
                        } else {
                            var idx = currentIndex - 1
                            if (idx >= 0 && idx < root.providers.length)
                                root.filterProviderId = root.providers[idx].id
                        }
                        root.applyFilter()
                    }
                    background: Rectangle {
                        color: Style.fieldBackground
                        border.color: Style.fieldBorder
                        border.width: 1
                        radius: 4
                        implicitHeight: 32
                    }
                    contentItem: Text {
                        text: providerFilterCombo.displayText
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
                        width: providerFilterCombo.width
                        contentItem: Text {
                            text: modelData
                            color: Style.textPrimary
                            font.pixelSize: 12
                            verticalAlignment: Text.AlignVCenter
                        }
                        background: Rectangle {
                            color: highlighted ? Style.surfaceVariant : Style.cardBackground
                        }
                        highlighted: providerFilterCombo.highlightedIndex === index
                    }
                }

                Text {
                    text: root.searchResults.length + " models"
                    font.pixelSize: 12
                    color: Style.textTertiary
                }
            }
        }

        // Main content: results list + optional detail panel
        RowLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            spacing: 12

            // Results list grouped by provider
            ScrollView {
                Layout.fillWidth: true
                Layout.fillHeight: true
                contentWidth: availableWidth
                clip: true

                ColumnLayout {
                    width: parent.width
                    spacing: 8

                    Repeater {
                        model: root.getGroupedResults()

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 4

                            property var group: modelData

                            // Provider group header
                            Rectangle {
                                Layout.fillWidth: true
                                Layout.preferredHeight: 40
                                color: Style.cardBackground
                                border.color: Style.cardBorder
                                border.width: 1
                                radius: 8

                                RowLayout {
                                    anchors.fill: parent
                                    anchors.margins: 10
                                    spacing: 8

                                    Text {
                                        text: group.providerName
                                        font.pixelSize: 13
                                        font.bold: true
                                        color: Style.textPrimary
                                    }

                                    // State badge
                                    Rectangle {
                                        width: badgeText.implicitWidth + 12
                                        height: 18
                                        radius: 9
                                        color: Style.colorForState(root.getProviderState(group.providerId))
                                        opacity: 0.2

                                        Text {
                                            id: badgeText
                                            anchors.centerIn: parent
                                            text: root.getProviderState(group.providerId)
                                            font.pixelSize: 10
                                            font.bold: true
                                            color: Style.colorForState(root.getProviderState(group.providerId))
                                        }
                                    }

                                    Text {
                                        text: group.models.length + " models"
                                        font.pixelSize: 11
                                        color: Style.textTertiary
                                    }

                                    Item { Layout.fillWidth: true }

                                    // Fetch Models button
                                    Button {
                                        onClicked: root.fetchModelsForProvider(group.providerId)
                                        enabled: !(root.loadingProviders[group.providerId] === true)

                                        background: Rectangle {
                                            color: Style.fieldBackground
                                            border.color: Style.fieldBorder
                                            border.width: 1
                                            radius: 4
                                            implicitWidth: 100
                                            implicitHeight: 24
                                        }
                                        contentItem: RowLayout {
                                            spacing: 4

                                            // Loading spinner
                                            Rectangle {
                                                visible: root.loadingProviders[group.providerId] === true
                                                width: 12; height: 12; radius: 6
                                                color: "transparent"
                                                border.color: Style.primary
                                                border.width: 2

                                                RotationAnimation on rotation {
                                                    loops: Animation.Infinite
                                                    from: 0; to: 360
                                                    duration: 1000
                                                }
                                            }

                                            Text {
                                                text: root.loadingProviders[group.providerId] === true
                                                      ? "Fetching..." : "Fetch Models"
                                                color: Style.textPrimary
                                                font.pixelSize: 11
                                                horizontalAlignment: Text.AlignHCenter
                                            }
                                        }
                                    }
                                }
                            }

                            // Model items in this group
                            Repeater {
                                model: group.models

                                Rectangle {
                                    Layout.fillWidth: true
                                    Layout.preferredHeight: 36
                                    Layout.leftMargin: 16
                                    color: modelMouseArea.containsMouse ? Style.surfaceVariant
                                         : (root.selectedModelName === modelData && root.selectedProviderId === group.providerId)
                                           ? Qt.rgba(Style.primary.r, Style.primary.g, Style.primary.b, 0.1)
                                           : "transparent"
                                    radius: 6

                                    RowLayout {
                                        anchors.fill: parent
                                        anchors.leftMargin: 12
                                        anchors.rightMargin: 12
                                        spacing: 8

                                        Text {
                                            text: modelData
                                            font.pixelSize: 12
                                            color: Style.textPrimary
                                            elide: Text.ElideMiddle
                                            Layout.fillWidth: true
                                        }

                                        Text {
                                            text: group.providerName
                                            font.pixelSize: 10
                                            color: Style.textTertiary
                                        }
                                    }

                                    MouseArea {
                                        id: modelMouseArea
                                        anchors.fill: parent
                                        hoverEnabled: true
                                        cursorShape: Qt.PointingHandCursor
                                        onClicked: root.selectModel(modelData, group.providerId)
                                    }
                                }
                            }
                        }
                    }

                    // Empty state
                    Text {
                        visible: root.searchResults.length === 0
                        text: root.searchQuery !== ""
                              ? "No models match \"" + root.searchQuery + "\""
                              : "No models loaded. Use Fetch Models to load model lists."
                        font.pixelSize: 14
                        color: Style.textHint
                        Layout.alignment: Qt.AlignHCenter
                        Layout.topMargin: 40
                    }

                    Item { Layout.fillHeight: true }
                }
            }

            // Detail panel (shown when a model is selected)
            Rectangle {
                visible: root.showDetail
                Layout.preferredWidth: 320
                Layout.fillHeight: true
                color: Style.cardBackground
                border.color: Style.cardBorder
                border.width: 1
                radius: 8

                ColumnLayout {
                    anchors.fill: parent
                    anchors.margins: 16
                    spacing: 12

                    // Close button
                    RowLayout {
                        Layout.fillWidth: true

                        Text {
                            text: "Model Details"
                            font.pixelSize: 14
                            font.bold: true
                            color: Style.textPrimary
                        }

                        Item { Layout.fillWidth: true }

                        Text {
                            text: "\u2715"
                            font.pixelSize: 16
                            color: Style.textSecondary

                            MouseArea {
                                anchors.fill: parent
                                cursorShape: Qt.PointingHandCursor
                                onClicked: root.showDetail = false
                            }
                        }
                    }

                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                    }

                    // Model name
                    Text {
                        text: "Model"
                        font.pixelSize: 11
                        color: Style.textSecondary
                    }

                    Text {
                        text: root.selectedModelName
                        font.pixelSize: 14
                        font.bold: true
                        color: Style.primary
                        wrapMode: Text.Wrap
                        Layout.fillWidth: true
                    }

                    // Provider
                    Text {
                        text: "Provider"
                        font.pixelSize: 11
                        color: Style.textSecondary
                        Layout.topMargin: 8
                    }

                    RowLayout {
                        spacing: 8

                        Text {
                            text: root.providerDisplayName(root.selectedProviderId)
                            font.pixelSize: 13
                            color: Style.textPrimary
                        }

                        Rectangle {
                            width: detailBadgeText.implicitWidth + 12
                            height: 18
                            radius: 9
                            color: Style.colorForState(root.getProviderState(root.selectedProviderId))
                            opacity: 0.2

                            Text {
                                id: detailBadgeText
                                anchors.centerIn: parent
                                text: root.getProviderState(root.selectedProviderId)
                                font.pixelSize: 10
                                font.bold: true
                                color: Style.colorForState(root.getProviderState(root.selectedProviderId))
                            }
                        }
                    }

                    // Provider ID
                    Text {
                        text: "Provider ID"
                        font.pixelSize: 11
                        color: Style.textSecondary
                        Layout.topMargin: 8
                    }

                    Text {
                        text: root.selectedProviderId
                        font.pixelSize: 12
                        color: Style.textTertiary
                        Layout.fillWidth: true
                    }

                    // Actions
                    Rectangle {
                        Layout.fillWidth: true
                        height: 1
                        color: Style.cardBorder
                        Layout.topMargin: 8
                    }

                    Button {
                        Layout.fillWidth: true
                        onClicked: {
                            viewModel.copyToClipboard(root.selectedModelName)
                        }
                        background: Rectangle {
                            color: Style.fieldBackground
                            border.color: Style.fieldBorder
                            border.width: 1
                            radius: 6
                            implicitHeight: 32
                        }
                        contentItem: Text {
                            text: "Copy Model Name"
                            color: Style.textPrimary
                            font.pixelSize: 12
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                    }

                    Button {
                        Layout.fillWidth: true
                        onClicked: {
                            viewModel.testProvider(root.selectedProviderId)
                        }
                        background: Rectangle {
                            color: Style.primary
                            radius: 6
                            implicitHeight: 32
                        }
                        contentItem: Text {
                            text: "Test Provider"
                            color: "#FFFFFF"
                            font.pixelSize: 12
                            font.bold: true
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                    }

                    Item { Layout.fillHeight: true }
                }
            }
        }
    }
}
