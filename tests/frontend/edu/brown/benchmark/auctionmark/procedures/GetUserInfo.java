package edu.brown.benchmark.auctionmark.procedures;

import org.apache.log4j.Logger;
import org.voltdb.ProcInfo;
import org.voltdb.SQLStmt;
import org.voltdb.VoltProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;

import edu.brown.benchmark.auctionmark.AuctionMarkConstants;

/**
 * GetUserInfo
 * 
 * @author pavlo
 * @author visawee
 */
@ProcInfo(
    partitionInfo = "USER.U_ID: 0",
    singlePartition = false
)
public class GetUserInfo extends VoltProcedure {
    private static final Logger LOG = Logger.getLogger(GetUserInfo.class);

    // -----------------------------------------------------------------
    // STATIC MEMBERS
    // -----------------------------------------------------------------
    
    private static final String ITEM_COLUMNS = "i_id, i_u_id, i_name, i_current_price, " +
                                               "i_num_bids, i_end_date, i_status";
    
    // -----------------------------------------------------------------
    // STATEMENTS
    // -----------------------------------------------------------------
    
    public final SQLStmt selectUser = new SQLStmt(
        "SELECT u_id, u_rating, u_created, u_balance, u_sattr0, u_sattr1, u_sattr2, u_sattr3, u_sattr4, r_name " +
          "FROM " + AuctionMarkConstants.TABLENAME_USER + ", " +
                    AuctionMarkConstants.TABLENAME_REGION + " " +
         "WHERE u_id = ? AND u_r_id = r_id"
    );

    public final SQLStmt selectUserFeedback = new SQLStmt(
        "SELECT u_id, u_rating, u_sattr0, u_sattr1, uf_rating, uf_date, uf_sattr0 " +
        "  FROM " + AuctionMarkConstants.TABLENAME_USER + ", " +
                    AuctionMarkConstants.TABLENAME_USER_FEEDBACK +
        " WHERE u_id = ? AND uf_u_id = u_id " +
        " ORDER BY uf_date DESC LIMIT 25 "
    );

    public final SQLStmt selectItemComments = new SQLStmt(
        "SELECT " + ITEM_COLUMNS +
        "       ic_id, ic_i_id, ic_u_id, ic_buyer_id, ic_date, ic_question " +
        "  FROM " + AuctionMarkConstants.TABLENAME_ITEM + ", " + 
                    AuctionMarkConstants.TABLENAME_ITEM_COMMENT +
        " WHERE i_u_id = ? AND i_status = " + AuctionMarkConstants.STATUS_ITEM_OPEN + 
        "   AND i_id = ic_i_id AND i_u_id = ic_u_id AND ic_response = ? " +
        " ORDER BY ic_date DESC LIMIT 25 "
    );
    
    public final SQLStmt selectSellerItems = new SQLStmt(
        "SELECT " + ITEM_COLUMNS +
         " FROM " + AuctionMarkConstants.TABLENAME_ITEM + " " +
         "WHERE i_u_id = ? " +
         "ORDER BY i_end_date DESC LIMIT 25 "
    );
    
    public final SQLStmt selectBuyerItems = new SQLStmt(
        "SELECT " + ITEM_COLUMNS +
         " FROM " + AuctionMarkConstants.TABLENAME_USER_ITEM + ", " +
                    AuctionMarkConstants.TABLENAME_ITEM +
        " WHERE ui_u_id = ? " +
           "AND ui_i_id = i_id AND ui_i_u_id = i_u_id " +
         "ORDER BY i_end_date DESC LIMIT 25 "
    );
    
    public final SQLStmt selectWatchedItems = new SQLStmt(
        "SELECT " + ITEM_COLUMNS + ", uw_u_id, uw_created " +
          "FROM " + AuctionMarkConstants.TABLENAME_USER_WATCH + ", " +
                    AuctionMarkConstants.TABLENAME_ITEM +
        " WHERE uw_u_id = ? " +
        "   AND uw_i_id = i_id AND uw_i_u_id = i_u_id " +
        " ORDER BY i_end_date DESC LIMIT 25"
    );
    

    // -----------------------------------------------------------------
    // RUN METHOD
    // -----------------------------------------------------------------
    
    public VoltTable[] run(long user_id, long get_feedback, long get_comments, long get_seller_items, long get_buyer_items, long get_watched_items) {
        final boolean debug = LOG.isDebugEnabled();
        
        // The first VoltTable in the output will always be the user's information
        if (debug) LOG.debug("Grabbing USER record: " + user_id);
        voltQueueSQL(selectUser, user_id);

        // They can also get their USER_FEEDBACK records if they want as well
        if (get_feedback != VoltType.NULL_BIGINT) {
            if (debug) LOG.debug("Grabbing USER_FEEDBACK records: " + user_id);
            voltQueueSQL(selectUserFeedback, user_id);
        }
        
        // And any pending ITEM_COMMENTS that need a response
        if (get_comments != VoltType.NULL_BIGINT) {
            if (debug) LOG.debug("Grabbing ITEM_COMMENT records: " + user_id);
            voltQueueSQL(selectItemComments, user_id, null);
        }
        
        // The seller's items
        if (get_seller_items != VoltType.NULL_BIGINT) {
            if (debug) LOG.debug("Grabbing seller's ITEM records: " + user_id);
            voltQueueSQL(selectSellerItems, user_id);
        }

        // The buyer's purchased items
        if (get_buyer_items != VoltType.NULL_BIGINT) {
            // 2010-11-15: The distributed query planner chokes on this one and makes a plan
            // that basically sends the entire user table to all nodes. So for now we'll just execute
            // the query to grab the buyer's feedback information
            // voltQueueSQL(select_seller_feedback, u_id);
            if (debug) LOG.debug("Grabbing buyer's USER_ITEM records: " + user_id);
            voltQueueSQL(selectBuyerItems, user_id);
        }
        
        // The buyer's watched items
        if (get_watched_items != VoltType.NULL_BIGINT) {
            if (debug) LOG.debug("Grabbing buyer's USER_WATCH records: " + user_id);
            voltQueueSQL(selectWatchedItems, user_id);
        }

        return (voltExecuteSQL(true));
    }
}