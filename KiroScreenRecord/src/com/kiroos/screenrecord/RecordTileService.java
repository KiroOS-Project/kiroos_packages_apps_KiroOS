/*
 * Copyright (C) 2026 KiroOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.kiroos.screenrecord;

import android.content.Intent;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** The control center tile: starts a recording (with its options), or stops the running one. */
public class RecordTileService extends TileService {

    @Override
    public void onStartListening() {
        update();
    }

    @Override
    public void onClick() {
        if (RecordService.sActive) {
            startService(new Intent(this, RecordService.class)
                    .setAction(RecordService.ACTION_STOP));
            return;
        }
        final Runnable open = new Runnable() {
            @Override
            public void run() {
                startActivityAndCollapse(new Intent(RecordTileService.this, StartActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        };
        if (isLocked()) {
            unlockAndRun(open);
        } else {
            open.run();
        }
    }

    private void update() {
        final Tile tile = getQsTile();
        if (tile == null) return;
        final boolean on = RecordService.sActive;
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(on ? R.string.tile_stop : R.string.tile_label));
        tile.updateTile();
    }
}
