/*
 * Copyright (C) 2024 ankio(ankio@ankio.net)
 * Licensed under the Apache License, Version 3.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-3.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 */

// Copied from AutoAccounting @ 34b38f82ca9ad6d7905c715c66891977f8809c88.
// Modification 2026-10-18: package only. Original header retained verbatim.
// Repository LICENSE is GPL v3; the conflicting Apache 3.0 notice is unresolved.
package dev.inkledger.core.upstream

enum class BillState {
    Synced, //已同步
    Wait2Edit, //等待编辑
    Edited, //已编辑
}