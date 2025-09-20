/*
 *  Copyright (C) 2011-2017 Cojen.org
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU Affero General Public License as
 *  published by the Free Software Foundation, either version 3 of the
 *  License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Affero General Public License for more details.
 *
 *  You should have received a copy of the GNU Affero General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.cojen.tupl;

/**
 * Thrown when the internal structure of the {@link Database database} is
 * corrupt.
 *
 * @author Brian S O'Neill
 */
public class CorruptDatabaseException extends DatabaseException {
    private static final long serialVersionUID = 1L;

    public CorruptDatabaseException() {
    }

    public CorruptDatabaseException(String message) {
        super(message);
    }

    public CorruptDatabaseException(Throwable cause) {
        super(cause);
    }

    public CorruptDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
