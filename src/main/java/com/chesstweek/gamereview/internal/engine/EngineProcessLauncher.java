package com.chesstweek.gamereview.internal.engine;

import java.io.IOException;
import java.nio.file.Path;

interface EngineProcessLauncher {

    EngineProcess launch(Path executable) throws IOException;
}
