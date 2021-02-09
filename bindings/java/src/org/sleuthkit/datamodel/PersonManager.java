/*
 * Sleuth Kit Data Model
 *
 * Copyright 2021 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.datamodel;

import com.google.common.base.Strings;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.sleuthkit.datamodel.SleuthkitCase.CaseDbConnection;

/**
 * Responsible for creating/updating/retrieving Persons.
 *
 */
public final class PersonManager {

	private final SleuthkitCase db;

	/**
	 * Construct a PersonManager for the given SleuthkitCase.
	 *
	 * @param skCase The SleuthkitCase
	 *
	 */
	PersonManager(SleuthkitCase skCase) {
		this.db = skCase;
	}
	
	/** 
	 * FOR TESTING ONLY
	 * Simple method to add a person to a host in order to test
	 * that deletion of persons is working as expected.
	 * 
	 * @param host_id
	 * @param person_id 
	 * 
	 * @throws TskCoreException 
	 */
	public void addPersonToHost(long host_id, long person_id) throws TskCoreException {
		String queryString = "UPDATE tsk_hosts SET person_id = " + person_id 
				+ " WHERE id = " + host_id;
		try (CaseDbConnection connection = this.db.getConnection();
				Statement s = connection.createStatement();) {
				s.executeUpdate(queryString);
		} catch (SQLException ex) {
			throw new TskCoreException(String.format("Error getting persons"), ex);
		}
	}
	
	/**
	 * Get all persons in the database.
	 * 
	 * @return List of persons
	 * 
	 * @throws TskCoreException 
	 */
	public List<Person> getPersons() throws TskCoreException {
		String queryString = "SELECT * FROM tsk_persons";

		List<Person> persons = new ArrayList<>();
		try (CaseDbConnection connection = this.db.getConnection();
				Statement s = connection.createStatement();
				ResultSet rs = connection.executeQuery(s, queryString)) {

			while (rs.next()) {
				persons.add(new Person(rs.getLong("id"), rs.getString("name")));
			}

			return persons;
		} catch (SQLException ex) {
			throw new TskCoreException(String.format("Error getting persons"), ex);
		}
	}
	
	/**
	 * Update the name of the person with the given id.
	 * 
	 * @param id Id of the person to update.
	 * @param newName New name for the person.
	 * 
	 * @throws TskCoreException 
	 */
	public void updatePerson(long id, String newName) throws TskCoreException {
		String queryString = "UPDATE tsk_persons"
				+ " SET name = ? WHERE id = " + id;
		try {
			CaseDbConnection connection = this.db.getConnection();
			PreparedStatement s = connection.getPreparedStatement(queryString, Statement.NO_GENERATED_KEYS);
			s.clearParameters();
			s.setString(1, newName);
			s.executeUpdate();
		} catch (SQLException ex) {
			throw new TskCoreException(String.format("Error updating person with id = %d to name %s", id, newName), ex);
		}		
	}
	
	/**
	 * Delete a person.
	 * 
	 * @param name Name of the person to delete
	 */
	public void deletePerson(String name) throws TskCoreException {
				String queryString = "DELETE FROm tsk_persons"
				+ " WHERE LOWER(name) = LOWER(?)";
		try {
			CaseDbConnection connection = this.db.getConnection();
			PreparedStatement s = connection.getPreparedStatement(queryString, Statement.NO_GENERATED_KEYS);
			s.clearParameters();
			s.setString(1, name);
			s.executeUpdate();
		} catch (SQLException ex) {
			throw new TskCoreException(String.format("Error deleting person with name %s", name), ex);
		}	
	}
	
	/**
	 * Get person with given name.
	 *
	 * @param name        Person name to look for.
	 *
	 * @return Optional with person. Optional.empty if no matching person is found.
	 *
	 * @throws TskCoreException
	 */
	public Optional<Person> getPerson(String name) throws TskCoreException {
		try (CaseDbConnection connection = this.db.getConnection()) {
			return getPerson(name, connection);
		}
	}	
	
	/**
	 * Create a person with specified name. If a person already exists with the
	 * given name, it returns the existing person.
	 *
	 * @param name	Person name.
	 *
	 * @return Person with the specified name.
	 *
	 * @throws TskCoreException
	 */
	public Person createPerson(String name) throws TskCoreException {

		// Must have a name
		if (Strings.isNullOrEmpty(name)) {
			throw new IllegalArgumentException("Host name is required.");
		}

		CaseDbConnection connection = this.db.getConnection();
		db.acquireSingleUserCaseWriteLock();
		try {
			String personInsertSQL = "INSERT INTO tsk_persons(name) VALUES (?)"; // NON-NLS
			PreparedStatement preparedStatement = connection.getPreparedStatement(personInsertSQL, Statement.RETURN_GENERATED_KEYS);

			preparedStatement.clearParameters();
			preparedStatement.setString(1, name);

			connection.executeUpdate(preparedStatement);

			// Read back the row id
			try (ResultSet resultSet = preparedStatement.getGeneratedKeys();) {
				if (resultSet.next()) {
					return new Person(resultSet.getLong(1), name); //last_insert_rowid()
				} else {
					throw new SQLException("Error executing SQL: " + personInsertSQL);
				}
			}
		} catch (SQLException ex) {
			// The insert may have failed because this person already exists, so try getting the person now.
			Optional<Person> person = this.getPerson(name, connection);
			if (person.isPresent()) {
				return person.get();
			} else {
				throw new TskCoreException(String.format("Error adding person with name = %s", name), ex);
			}
		} finally {
			db.releaseSingleUserCaseWriteLock();
		}
	}
	
	/**
	 * Get person with given name.
	 *
	 * @param name       Person name to look for.
	 * @param connection Database connection to use.
	 *
	 * @return Optional with person. Optional.empty if no matching person is found.
	 *
	 * @throws TskCoreException
	 */
	private Optional<Person> getPerson(String name, CaseDbConnection connection) throws TskCoreException {

		String queryString = "SELECT * FROM tsk_persons"
				+ " WHERE LOWER(name) = LOWER(?)";
		try {
			PreparedStatement s = connection.getPreparedStatement(queryString, Statement.RETURN_GENERATED_KEYS);
			s.clearParameters();
			s.setString(1, name);

			try (ResultSet rs = s.executeQuery()) {
				if (!rs.next()) {
					return Optional.empty();	// no match found
				} else {
					return Optional.of(new Person(rs.getLong("id"), rs.getString("name")));
				}
			}
		} catch (SQLException ex) {
			throw new TskCoreException(String.format("Error getting person with name = %s", name), ex);
		}
	}	
	
}