/*
 * Copyright (C) 2020 Grakn Labs
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package grakn.core.kb.graql.executor.property;

import graql.lang.property.VarProperty;
import graql.lang.statement.Variable;

public interface PropertyExecutorFactory {
    PropertyExecutor create(Variable var, VarProperty property);
    PropertyExecutor.Definable definable(Variable var, VarProperty property);
    PropertyExecutor.Insertable insertable(Variable var, VarProperty property);
    PropertyExecutor.Deletable deletable(Variable var, VarProperty property);
}
