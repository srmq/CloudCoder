// CloudCoder - a web-based pedagogical programming environment
// Copyright (C) 2011-2014, Jaime Spacco <jspacco@knox.edu>
// Copyright (C) 2011-2014, David H. Hovemeyer <david.hovemeyer@gmail.com>
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

package org.cloudcoder.app.server.rpc;

import org.cloudcoder.app.client.rpc.AchievementService;
import org.cloudcoder.app.server.persist.Database;
import org.cloudcoder.app.server.persist.IDatabase;
import org.cloudcoder.app.shared.model.CloudCoderAuthenticationException;
import org.cloudcoder.app.shared.model.Course;
import org.cloudcoder.app.shared.model.User;
import org.cloudcoder.app.shared.model.UserAchievementAndAchievement;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;

/**
 * @author David Hovemeyer
 */
public class AchievementServiceImpl extends RemoteServiceServlet implements AchievementService {
	private static final long serialVersionUID = 1L;

	@Override
	public UserAchievementAndAchievement[] getUserAchievements(Course course) throws CloudCoderAuthenticationException {
		User currentUser =
				ServletUtil.checkClientIsAuthenticated(getThreadLocalRequest(), AchievementServiceImpl.class);
		
		IDatabase db = Database.getInstance();

		return db.getUserAchievements(currentUser, course);
	}

}
