package org.duofold.live.root;

import android.os.Binder;

public final class FoldWakeRootService extends BaseRootService {
    @Override protected Binder create() { return new org.duofold.live.FoldWakeService(); }
}
