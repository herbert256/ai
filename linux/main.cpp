#include <QApplication>
#include <QQmlApplicationEngine>
#include <QQmlContext>
#include <QIcon>
#include "viewmodels/AppViewModel.h"

int main(int argc, char *argv[])
{
    QApplication app(argc, argv);
    app.setApplicationName("AI");
    app.setOrganizationName("AI");
    app.setApplicationVersion("1.0");

    QQmlApplicationEngine engine;

    AppViewModel viewModel;
    engine.rootContext()->setContextProperty("viewModel", &viewModel);

    using namespace Qt::StringLiterals;
    const QUrl url(u"qrc:/AI/qml/main.qml"_s);
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed,
                     &app, []() { QCoreApplication::exit(-1); },
                     Qt::QueuedConnection);
    engine.load(url);

    viewModel.bootstrap();

    return app.exec();
}
