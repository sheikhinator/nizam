#!/bin/bash
# Script to extract, fix imports, and re-zip the nizam project

# Extract the ZIP file
unzip -q nizam-android.zip

# Create the fixed NizamDatabase.kt content
cat > nizam/app/src/main/java/com/nizam/app/data/db/NizamDatabase.kt << 'EOF'
package com.nizam.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.nizam.app.feature.news.data.Article
import com.nizam.app.data.db.NewsDao

@Database(
    entities = [
        Article::class,
    ],
    version = 1,
    exportSchema = false
)
abstract class NizamDatabase : RoomDatabase() {
    abstract fun newsDao(): NewsDao
}
EOF

# Remove the old ZIP and create a new one
rm nizam-android.zip
cd nizam
zip -r -q ../nizam-android.zip .
cd ..

echo "✓ Fixed nizam-android.zip with corrected imports"
