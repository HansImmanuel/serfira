package com.serfira.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test-only cleanup for tables that V12 made append-only in the database
 * ({@code block_modification} on UPDATE/DELETE), where a suite legitimately needs to remove its own
 * fixture rows afterwards.
 *
 * <p>The database trigger must never be weakened for tests. Instead this helper deletes within a
 * single connection that sets {@code session_replication_role = replica} (which PostgreSQL honours
 * only for a superuser — the Testcontainers role is one), runs the delete, and resets the role
 * before the connection returns to the pool. The bypass is therefore scoped to the one delete and
 * cannot leak into another connection or test.
 *
 * <p>Production code never gets this bypass: the application role is not a superuser, so
 * {@code system_parameter} stays append-only in every real environment (Addendum §1.2).
 *
 * <p>The predicate is a bound {@link PreparedStatement} with {@code ?} placeholders and caller-supplied
 * arguments — the same query hygiene as the {@code jdbc.update(sql, args...)} cleanups this helper
 * replaced — so no value is ever interpolated into the SQL string.
 */
public final class AppendOnlyTestCleanup {

	private AppendOnlyTestCleanup() {
	}

	/**
	 * Delete {@code system_parameter} rows matching {@code whereClause} with the append-only trigger
	 * temporarily bypassed on a single connection.
	 *
	 * @param jdbc        the Testcontainers {@link JdbcTemplate} (its role must be a superuser)
	 * @param whereClause the SQL predicate after {@code WHERE}, using {@code ?} placeholders only
	 * @param args        the bind values for the placeholders, in order
	 */
	public static void deleteSystemParameters(JdbcTemplate jdbc, String whereClause, Object... args) {
		jdbc.execute((Connection connection) -> {
			boolean previousAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try (Statement roleStatement = connection.createStatement()) {
				roleStatement.execute("set session_replication_role = replica");
				try (PreparedStatement delete =
						connection.prepareStatement("delete from system_parameter where " + whereClause)) {
					for (int i = 0; i < args.length; i++) {
						delete.setObject(i + 1, args[i]);
					}
					delete.executeUpdate();
				}
				roleStatement.execute("set session_replication_role = default");
				connection.commit();
			}
			catch (RuntimeException | SQLException ex) {
				connection.rollback();
				throw ex;
			}
			finally {
				connection.setAutoCommit(previousAutoCommit);
			}
			return null;
		});
	}
}
