package ru.openfs.lbpay.lbsoap.model;

	public enum LbCodeExternType {
		VG_LOGIN(0), // vgroups.login
		USER_LOGIN(1), // accounts.login
		TEL_STAFF(2), // tel_staff.phone_number ( ts join vgroups v on ts.vg_id = v.vg_id )
		STAFF(3), // staff.segment ( ts join vgroups v on ts.vg_id = v.vg_id )
		FIO(4), // accounts.name
		AGRM_NUM(5), // agreements.number
		// KOD_1C(6),
		AGRM_CODE(6), // agreements.code
		EMAIL(7), // accounts.email
		ORDER(8), VG_ID(9), // vgroups.vg_id
		UID(10), // accounts.uid
		AGRM_ID(11), // agreements.agrm_id
		SBRF_RYAZAN(12), // agreements.number, agreements_addons_vals.str_value
		AGRM_ADDON(13), // options, name = old_agreement
		ORDER_ID(14), // orders.order_id
		VG_LOGIN_SUFFIX(15), // part of vgroups.login, using options.name = extern_payment_regexp_suffix
		AGRM_NUM_TYPE(16), // Saratov's agreement number with type of account
		OPER_ID(17); // agreements.oper_id

		private final long code;

		LbCodeExternType(long code) {
			this.code = code;
		}

		public long getCode() {
			return this.code;
		}
	}