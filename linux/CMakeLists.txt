cmake_minimum_required(VERSION 3.16)
project(AI VERSION 1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_AUTOMOC ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Quick Network Widgets Concurrent)
qt_standard_project_setup(REQUIRES 6.5)

qt_add_executable(ai-app
    main.cpp

    data/AppService.h data/AppService.cpp
    data/DataModels.h
    data/ApiModels.h
    data/ApiClient.h data/ApiClient.cpp
    data/AnalysisRepository.h data/AnalysisRepository.cpp
    data/AnalysisProviders.h data/AnalysisProviders.cpp
    data/AnalysisStreaming.h data/AnalysisStreaming.cpp
    data/AnalysisChat.h data/AnalysisChat.cpp
    data/AnalysisModels.h data/AnalysisModels.cpp
    data/ReportStorage.h data/ReportStorage.cpp
    data/ChatHistoryManager.h data/ChatHistoryManager.cpp
    data/PricingCache.h data/PricingCache.cpp
    data/ProviderRegistry.h data/ProviderRegistry.cpp
    data/ApiTracer.h data/ApiTracer.cpp

    viewmodels/AppViewModel.h viewmodels/AppViewModel.cpp
    viewmodels/SettingsModels.h viewmodels/SettingsModels.cpp
    viewmodels/UiModels.h

    helpers/AppColors.h
    helpers/UiFormatting.h helpers/UiFormatting.cpp
    helpers/SettingsPreferences.h helpers/SettingsPreferences.cpp
    helpers/SettingsExport.h helpers/SettingsExport.cpp
    helpers/ReportExporter.h helpers/ReportExporter.cpp
)

qt_add_qml_module(ai-app
    URI AI
    VERSION 1.0
    QML_FILES
        qml/main.qml
        qml/theme/Style.qml
        qml/HubView.qml
        qml/reports/NewReportView.qml
        qml/reports/ReportProgressView.qml
        qml/reports/ReportSelectionDialog.qml
        qml/reports/ReportParametersView.qml
        qml/reports/ContentDisplay.qml
        qml/chat/ChatView.qml
        qml/chat/DualChatView.qml
        qml/models/ModelSearchView.qml
        qml/history/HistoryView.qml
        qml/history/PromptHistoryView.qml
        qml/settings/SettingsView.qml
        qml/settings/SetupView.qml
        qml/settings/ServiceSettingsView.qml
        qml/settings/AgentsView.qml
        qml/settings/FlocksView.qml
        qml/settings/SwarmsView.qml
        qml/settings/ParametersView.qml
        qml/settings/PromptsView.qml
        qml/settings/SystemPromptsView.qml
        qml/settings/EndpointsView.qml
        qml/admin/StatisticsView.qml
        qml/admin/HousekeepingView.qml
        qml/admin/DeveloperView.qml
        qml/admin/TraceView.qml
        qml/admin/HelpView.qml
    RESOURCES
        resources/setup.json
)

target_link_libraries(ai-app PRIVATE
    Qt6::Quick
    Qt6::Network
    Qt6::Widgets
    Qt6::Concurrent
)

target_include_directories(ai-app PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)
