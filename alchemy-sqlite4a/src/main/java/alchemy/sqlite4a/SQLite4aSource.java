/*
 * Copyright (C) 2017 exzogeni.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package alchemy.sqlite4a;

import alchemy.sqlite.SQLiteSource;
import alchemy.sqlite.platform.SQLiteSchema;

public class SQLite4aSource extends SQLiteSource {

    public SQLite4aSource(SQLiteSchema schema, String path) {
        super(new SQLite4aDriver(SQLite4aHook.NONE), schema, path);
    }

    public SQLite4aSource(SQLiteSchema schema, String path, SQLite4aHook hook) {
        super(new SQLite4aDriver(hook), schema, path);
    }

}
